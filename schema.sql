-- =============================================================
-- 情侣存款 · Supabase 数据库 schema
-- 执行方式：Supabase 后台 → SQL Editor → 粘贴全部 → Run
-- =============================================================

-- 1) 个人资料表（注册时由触发器自动建行）
create table if not exists public.profiles (
  id           uuid primary key references auth.users(id) on delete cascade,
  email        text,
  display_name text,
  invite_code  text unique not null,
  couple_id    uuid,
  created_at   timestamptz default now()
);

-- 2) 情侣关系表
create table if not exists public.couples (
  id         uuid primary key default gen_random_uuid(),
  partner_a  uuid references public.profiles(id),
  partner_b  uuid references public.profiles(id),
  created_at timestamptz default now()
);

-- 3) 资产账户（存款 / 理财 / 积存金）
create table if not exists public.accounts (
  id         uuid primary key default gen_random_uuid(),
  couple_id  uuid references public.couples(id) on delete cascade,
  owner_id   uuid references public.profiles(id),
  type       text not null check (type in ('deposit','wealth','gold')),
  name       text not null default '',
  balance    numeric not null default 0,   -- 存款:元 | 理财:净值元 | 积存金:克
  principal  numeric not null default 0,   -- 理财:本金元 | 积存金:累计成本元
  created_at timestamptz default now()
);
create index if not exists accounts_couple_idx on public.accounts(couple_id);

-- 4) 收支流水
create table if not exists public.transactions (
  id              uuid primary key default gen_random_uuid(),
  couple_id       uuid references public.couples(id) on delete cascade,
  owner_id        uuid references public.profiles(id),
  type            text not null check (type in ('income','expense')),
  amount          numeric not null default 0,
  category        text not null default '',
  note            text not null default '',
  created_by_name text not null default '',
  created_at      timestamptz default now()
);
create index if not exists txns_couple_idx on public.transactions(couple_id);

-- =============================================================
-- 注册即自动建 profile（含 8 位邀请码）
-- =============================================================
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer as $$
begin
  insert into public.profiles (id, email, invite_code)
  values (new.id, new.email, substr(md5(random()::text || new.id::text), 1, 8));
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- =============================================================
-- 配对函数（SECURITY DEFINER 绕过 RLS，同时更新双方 couple_id）
--   调用：POST /rest/v1/rpc/pair_with_invite  body: {"p_invite":"xxxx"}
-- =============================================================
create or replace function public.pair_with_invite(p_invite text)
returns void language plpgsql security definer as $$
declare
  v_my      uuid := auth.uid();
  v_partner uuid;
  v_couple  uuid;
begin
  if v_my is null then raise exception '未登录'; end if;
  select id into v_partner from public.profiles where invite_code = p_invite and id <> v_my;
  if v_partner is null then raise exception '邀请码无效'; end if;

  select couple_id into v_couple from public.profiles where id = v_my;
  if v_couple is null then
    select couple_id into v_couple from public.profiles where id = v_partner;
  end if;
  if v_couple is null then
    insert into public.couples (partner_a, partner_b)
    values (v_my, v_partner) returning id into v_couple;
  end if;

  update public.profiles set couple_id = v_couple where id in (v_my, v_partner);
end;
$$;

grant execute on function public.pair_with_invite(text) to authenticated, anon;

-- =============================================================
-- 行级安全（RLS）：每对情侣只能看/改自己的数据
-- =============================================================
alter table public.profiles    enable row level security;
alter table public.couples     enable row level security;
alter table public.accounts    enable row level security;
alter table public.transactions enable row level security;

-- profiles：自己 + 自己的对象可读；只允许改自己
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles for select
  using ( id = auth.uid()
          or couple_id = (select couple_id from public.profiles where id = auth.uid()) );

drop policy if exists profiles_update on public.profiles;
create policy profiles_update on public.profiles for update
  using (id = auth.uid()) with check (id = auth.uid());

-- couples：仅双方可读、创建者能插入
drop policy if exists couples_select on public.couples;
create policy couples_select on public.couples for select
  using (partner_a = auth.uid() or partner_b = auth.uid());
drop policy if exists couples_insert on public.couples;
create policy couples_insert on public.couples for insert
  with check (partner_a = auth.uid());

-- accounts / transactions：仅本对情侣可读写（couple_id 一致）
drop policy if exists accounts_all on public.accounts;
create policy accounts_all on public.accounts for all
  using ( couple_id = (select couple_id from public.profiles where id = auth.uid()) )
  with check ( couple_id = (select couple_id from public.profiles where id = auth.uid()) );

drop policy if exists txns_all on public.transactions;
create policy txns_all on public.transactions for all
  using ( couple_id = (select couple_id from public.profiles where id = auth.uid()) )
  with check ( couple_id = (select couple_id from public.profiles where id = auth.uid()) );

-- 5) 每日资产快照（收益折线图的历史数据源）
--    App 每次刷新会自动 upsert 当天的净资产 / 积存金盈亏，折线图从这里读历史
create table if not exists public.snapshots (
  id              uuid primary key default gen_random_uuid(),
  couple_id       uuid references public.couples(id) on delete cascade,
  snapshot_date   date not null default current_date,
  net_worth       numeric not null default 0,   -- 共同净资产(元)
  deposit_total   numeric not null default 0,   -- 存款合计(元)
  wealth_total    numeric not null default 0,   -- 理财净值合计(元)
  gold_grams      numeric not null default 0,   -- 积存金持有克数
  gold_value      numeric not null default 0,   -- 积存金实时市值(元)
  gold_profit     numeric not null default 0,   -- 积存金浮动盈亏(元)
  created_at      timestamptz default now(),
  updated_at      timestamptz default now(),
  unique (couple_id, snapshot_date)             -- 每对情侣每天一条，upsert 幂等
);

alter table public.snapshots enable row level security;
drop policy if exists snapshots_all on public.snapshots;
create policy snapshots_all on public.snapshots for all
  using ( couple_id = (select couple_id from public.profiles where id = auth.uid()) )
  with check ( couple_id = (select couple_id from public.profiles where id = auth.uid()) );
