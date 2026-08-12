package com.couplesavings.couplesavings.data

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

    private fun authHeaders(): Map<String, String> {
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
    suspend fun signUp(email: String, password: String): AuthUser {
        val body = json.encodeToString(mapOf("email" to email, "password" to password))
        val resp = httpRequest("POST", "${SupabaseConfig.URL}/auth/v1/signup", body, anonHeaders())
        val el = json.parseToJsonElement(resp).jsonObject
        val userEl = el["user"]!!.jsonObject
        val id = userEl["id"]!!.jsonPrimitive.content
        val mail = userEl["email"]?.jsonPrimitive?.content
        // 注册成功且关闭了邮箱确认时，session 里同时带回 access/refresh 双令牌
        el["session"]?.jsonObject?.let { s ->
            val at = s["access_token"]?.jsonPrimitive?.content
            val rt = s["refresh_token"]?.jsonPrimitive?.content.orEmpty()
            at?.let { SessionManager.saveTokens(it, rt) }
        }
        return AuthUser(id, mail)
    }

    suspend fun signIn(email: String, password: String): AuthUser {
        val body = json.encodeToString(mapOf("email" to email, "password" to password))
        val resp = httpRequest(
            "POST",
            "${SupabaseConfig.URL}/auth/v1/token?grant_type=password",
            body, anonHeaders()
        )
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
        val body = json.encodeToString(mapOf("refresh_token" to rt))
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
        return json.decodeFromString<List<Profile>>(resp).first()
    }

    suspend fun pair(inviteCode: String) {
        val body = json.encodeToString(mapOf("p_invite" to inviteCode))
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
                "${SupabaseConfig.URL}/rest/v1/accounts?couple_id=eq.${me.couple_id}&select=*&order=created_at.desc",
                null, authHeaders()
            )
        }
        return json.decodeFromString(resp)
    }

    suspend fun insertAccount(acc: Account) {
        val me = myProfile()
        val row = acc.copy(owner_id = me.id, couple_id = me.couple_id)
        val body = json.encodeToString(listOf(row))
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
        val body = json.encodeToString(patch)
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
                "${SupabaseConfig.URL}/rest/v1/transactions?couple_id=eq.${me.couple_id}&select=*&order=created_at.desc",
                null, authHeaders()
            )
        }
        return json.decodeFromString(resp)
    }

    suspend fun insertTransaction(t: TransactionRow) {
        val me = myProfile()
        val row = t.copy(owner_id = me.id, couple_id = me.couple_id)
        val body = json.encodeToString(listOf(row))
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
}
