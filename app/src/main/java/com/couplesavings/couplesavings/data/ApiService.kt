package com.couplesavings.couplesavings.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [INPUT]: 依赖 SupabaseConfig / SessionManager / httpRequest / Models
 * [OUTPUT]: 对外提供认证、配对、账户与流水的全部增删改查
 * [POS]: data 层门面，UI 层只调这里，不直接碰 HTTP
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 *
 * 关键约定：所有数据请求都带 apikey + Bearer<用户JWT>，
 * 这样 Supabase 的 RLS 才能识别 auth.uid() 并做行级隔离。
 *
 * 自动续期：访问令牌默认约 1 小时过期。withAuth() 在请求返回 401 时，
 * 用 refresh_token 静默换发新令牌并重试一次，使两人几乎无感地保持登录，
 * 不再需要每小时手动重登（MVP 痛点已解决）。
 */
object ApiService {
    private val json = Json { ignoreUnknownKeys = true }

    /** refresh 彻底失败时抛出，调用方据此清除会话并回到登录页 */
    class RefreshFailedException(msg: String) : Exception(msg)

    private fun anonHeaders(): Map<String, String> = mapOf(
        "apikey" to SupabaseConfig.ANON_KEY,
        "Authorization" to "Bearer ${SupabaseConfig.ANON_KEY}",
        "Content-Type" to "application/json"
    )

    // 注意：getToken() 是 suspend，故 authHeaders 也必须是 suspend
    private suspend fun authHeaders(): Map<String, String> {
        val token = SessionManager.getToken().orEmpty()
        return mapOf(
            "apikey" to SupabaseConfig.ANON_KEY,
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
    }

    /**
     * 带自动续期的请求包装：命中 401 时刷新令牌并重试一次。
     * 刷新本身失败则清除会话并向上抛 RefreshFailedException。
     */
    private suspend fun <T> withAuth(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: ApiException) {
            if (e.code == 401) {
                refreshSession()
                block()
            } else {
                throw e
            }
        } catch (e: RefreshFailedException) {
            SessionManager.clear()
            throw e
        }
    }

    // ---------------- 认证 ----------------

    /**
     * 把 Supabase 认证接口的常见英文错误翻译成人话，避免 UI 出现 "HTTP 400: {...}" 天书。
     * 仅拦截 4xx（客户端可自查的认证错误），其余原样上抛。
     */
    private fun translateAuthError(e: ApiException): ApiException {
        if (e.code !in 400..499) return e
        val err = runCatching {
            json.parseToJsonElement(e.body).jsonObject["error"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
        val msg = when {
            err.contains("email_not_confirmed", ignoreCase = true) ->
                "邮箱尚未验证：请在 Supabase 后台 Authentication → Providers → Email 关闭「Confirm email」，或先完成邮箱验证后再登录。"
            err.contains("invalid", ignoreCase = true) || err.contains("wrong", ignoreCase = true) ->
                "邮箱或密码不正确。"
            e.body.contains("User already registered", ignoreCase = true) ->
                "该邮箱已注册过：请直接点「登录」；若之前注册未验证，请先在 Supabase 后台删除该账户再重新注册。"
            else -> "认证失败(${e.code})：${e.body.take(160)}"
        }
        return ApiException(0, msg)
    }

    suspend fun signUp(email: String, password: String): AuthUser {
        val body = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("email" to email, "password" to password)
        )
        val resp = try {
            httpRequest("POST", "${SupabaseConfig.URL}/auth/v1/signup", body, anonHeaders())
        } catch (e: ApiException) {
            throw translateAuthError(e)
        }
        val el = json.parseToJsonElement(resp).jsonObject
        val userEl = el["user"]!!.jsonObject
        val id = userEl["id"]!!.jsonPrimitive.content
        val mail = userEl["email"]?.jsonPrimitive?.content
        // 注册接口返回了用户却没给会话，几乎都是因为 Supabase 开启了「邮箱确认」
        val session = el["session"]?.jsonObject
        if (session == null) {
            throw ApiException(
                0,
                "账户已创建，但 Supabase 未发放登录会话——多半是后台开启了「邮箱确认(Confirm email)」。\n" +
                "请到 Supabase 后台 Authentication → Providers → Email 关闭 Confirm email，" +
                "并删除这个未验证的测试账户后重新注册。"
            )
        }
        val at = session["access_token"]?.jsonPrimitive?.content
        val rt = session["refresh_token"]?.jsonPrimitive?.content.orEmpty()
        at?.let { SessionManager.saveTokens(it, rt) }
        return AuthUser(id, mail)
    }

    suspend fun signIn(email: String, password: String): AuthUser {
        val body = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("email" to email, "password" to password)
        )
        val resp = try {
            httpRequest(
                "POST",
                "${SupabaseConfig.URL}/auth/v1/token?grant_type=password",
                body, anonHeaders()
            )
        } catch (e: ApiException) {
            throw translateAuthError(e)
        }
        val el = json.parseToJsonElement(resp).jsonObject
        val id = el["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val mail = el["user"]!!.jsonObject["email"]?.jsonPrimitive?.content
        val token = el["access_token"]!!.jsonPrimitive.content
        val refresh = el["refresh_token"]?.jsonPrimitive?.content.orEmpty()
        SessionManager.saveTokens(token, refresh)
        return AuthUser(id, mail)
    }

    suspend fun signOut() {
        runCatching { httpRequest("POST", "${SupabaseConfig.URL}/auth/v1/logout", "", authHeaders()) }
        SessionManager.clear()
    }

    /**
     * 用 refresh_token 换发新的 access_token。失败抛 RefreshFailedException。
     * 端点是 Supabase 标准刷新接口，只需 anon apikey 头、无需有效 access_token。
     */
    suspend fun refreshSession() {
        val rt = SessionManager.getRefreshToken()
            ?: throw RefreshFailedException("会话已失效，请重新登录")
        val body = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("refresh_token" to rt)
        )
        val resp = runCatching {
            httpRequest(
                "POST",
                "${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token",
                body, anonHeaders()
            )
        }.getOrElse { throw RefreshFailedException("刷新请求失败：${it.message}") }

        val el = json.parseToJsonElement(resp).jsonObject
        val newAccess = el["access_token"]?.jsonPrimitive?.content
            ?: throw RefreshFailedException("刷新响应缺少 access_token")
        // 旋转刷新令牌：优先用服务端返回的新 refresh_token，缺失时沿用旧的
        val newRefresh = el["refresh_token"]?.jsonPrimitive?.content ?: rt
        SessionManager.saveTokens(newAccess, newRefresh)
    }

    suspend fun currentUser(): AuthUser {
        val resp = withAuth {
            httpRequest("GET", "${SupabaseConfig.URL}/auth/v1/user", null, authHeaders())
        }
        val el = json.parseToJsonElement(resp).jsonObject
        return AuthUser(
            el["id"]!!.jsonPrimitive.content,
            el["email"]?.jsonPrimitive?.content
        )
    }

    // ---------------- 资料 / 配对 ----------------
    suspend fun myProfile(): Profile {
        val me = currentUser()
        val resp = withAuth {
            httpRequest(
                "GET",
                "${SupabaseConfig.URL}/rest/v1/profiles?id=eq.${me.id}&select=*",
                null, authHeaders()
            )
        }
        val list = json.decodeFromString<List<Profile>>(resp)
        if (list.isEmpty()) {
            throw ApiException(
                0,
                "未找到你的个人资料(profiles 表无记录)。请确认已在 Supabase 执行过 schema.sql，" +
                "且注册时触发器 on_auth_user_created 已为账户创建 profile 行。"
            )
        }
        return list.first()
    }

    suspend fun pair(inviteCode: String) {
        val body = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("p_invite" to inviteCode)
        )
        withAuth {
            httpRequest(
                "POST",
                "${SupabaseConfig.URL}/rest/v1/rpc/pair_with_invite",
                body, authHeaders()
            )
        }
    }

    // ---------------- 账户 ----------------
    suspend fun listAccounts(): List<Account> {
        val me = myProfile()
        val resp = withAuth {
            httpRequest(
                "GET",
                "${SupabaseConfig.URL}/rest/v1/accounts?select=*&order=created_at.desc",
                null, authHeaders()
            )
        }
        return json.decodeFromString(resp)
    }

    suspend fun insertAccount(acc: Account) {
        val me = myProfile()
        val row = acc.copy(owner_id = me.id, couple_id = me.couple_id)
        val body = json.encodeToString(ListSerializer(Account.serializer()), listOf(row))
        withAuth {
            httpRequest(
                "POST", "${SupabaseConfig.URL}/rest/v1/accounts", body,
                authHeaders() + ("Prefer" to "return=representation")
            )
        }
    }

    suspend fun updateAccount(acc: Account) {
        val patch = AccountPatch(
            name = acc.name,
            type = acc.type,
            balance = acc.balance,
            principal = acc.principal
        )
        val body = json.encodeToString(AccountPatch.serializer(), patch)
        withAuth {
            httpRequest(
                "PATCH", "${SupabaseConfig.URL}/rest/v1/accounts?id=eq.${acc.id}", body, authHeaders()
            )
        }
    }

    suspend fun deleteAccount(id: String) {
        withAuth {
            httpRequest("DELETE", "${SupabaseConfig.URL}/rest/v1/accounts?id=eq.$id", null, authHeaders())
        }
    }

    // ---------------- 流水 ----------------
    suspend fun listTransactions(): List<TransactionRow> {
        val me = myProfile()
        val resp = withAuth {
            httpRequest(
                "GET",
                "${SupabaseConfig.URL}/rest/v1/transactions?select=*&order=created_at.desc",
                null, authHeaders()
            )
        }
        return json.decodeFromString(resp)
    }

    suspend fun insertTransaction(t: TransactionRow) {
        val me = myProfile()
        val row = t.copy(owner_id = me.id, couple_id = me.couple_id)
        val body = json.encodeToString(ListSerializer(TransactionRow.serializer()), listOf(row))
        withAuth {
            httpRequest(
                "POST", "${SupabaseConfig.URL}/rest/v1/transactions", body,
                authHeaders() + ("Prefer" to "return=representation")
            )
        }
    }

    suspend fun deleteTransaction(id: String) {
        withAuth {
            httpRequest("DELETE", "${SupabaseConfig.URL}/rest/v1/transactions?id=eq.$id", null, authHeaders())
        }
    }

    // ---------------- 每日资产快照（折线图） ----------------
    /** 幂等写入当天快照：按 (couple_id, snapshot_date) 冲突则更新 */
    suspend fun upsertSnapshot(snap: Snapshot) {
        val me = myProfile()
        val row = snap.copy(couple_id = me.couple_id)
        val body = json.encodeToString(ListSerializer(Snapshot.serializer()), listOf(row))
        withAuth {
            httpRequest(
                "POST",
                "${SupabaseConfig.URL}/rest/v1/snapshots?on_conflict=couple_id,snapshot_date",
                body,
                authHeaders() + ("Prefer" to "upsert")
            )
        }
    }

    /** 读取本对情侣的全部历史快照，按日期升序 */
    suspend fun listSnapshots(): List<Snapshot> {
        val me = myProfile()
        val resp = withAuth {
            httpRequest(
                "GET",
                "${SupabaseConfig.URL}/rest/v1/snapshots?select=*&order=snapshot_date.asc",
                null, authHeaders()
            )
        }
        return json.decodeFromString(resp)
    }
}
