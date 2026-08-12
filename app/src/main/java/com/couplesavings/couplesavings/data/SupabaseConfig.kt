package com.couplesavings.couplesavings.data

/**
 * [INPUT]: 无（纯配置）
 * [OUTPUT]: 对外提供 Supabase 项目地址与匿名公钥两个常量
 * [POS]: data 层根配置，所有网络请求从这里取 URL / ANON_KEY
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 *
 * ！！！上线前必须替换成你自己的 Supabase 项目值！！！
 * 位置：Supabase 后台 → Project Settings → API
 *   - URL 形如 https://xxxxxxxx.supabase.co
 *   - anon key 是那串很长的 public key
 */
object SupabaseConfig {
    const val URL = "https://YOUR_PROJECT_REF.supabase.co"
    const val ANON_KEY = "YOUR_ANON_KEY"
}
