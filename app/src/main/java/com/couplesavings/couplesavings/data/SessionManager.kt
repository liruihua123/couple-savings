package com.couplesavings.couplesavings.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * [INPUT]: 依赖 Android Context 与 androidx.datastore
 * [OUTPUT]: 对外提供 access_token 与 refresh_token 的保存/读取/清除
 * [POS]: data 层会话存储，登录后持久化 Supabase 双令牌，重启 App 仍有效
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
private val Context.dataStore by preferencesDataStore("session")
private val KEY_TOKEN = stringPreferencesKey("jwt")
private val KEY_REFRESH = stringPreferencesKey("refresh_token")
private val KEY_EMAIL = stringPreferencesKey("email")
private val KEY_PASSWORD = stringPreferencesKey("password")
private val KEY_REMEMBER = stringPreferencesKey("remember")

object SessionManager {
    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    /** 兼容旧调用：仅保存 access_token，refresh 置空 */
    suspend fun saveToken(t: String) {
        ctx.dataStore.edit { it[KEY_TOKEN] = t; it[KEY_REFRESH] = "" }
    }

    /** 同时保存访问令牌与刷新令牌 */
    suspend fun saveTokens(access: String, refresh: String) {
        ctx.dataStore.edit { it[KEY_TOKEN] = access; it[KEY_REFRESH] = refresh }
    }

    suspend fun getToken(): String? = ctx.dataStore.data.first()[KEY_TOKEN]

    suspend fun getRefreshToken(): String? = ctx.dataStore.data.first()[KEY_REFRESH]

    suspend fun clear() {
        ctx.dataStore.edit { it.remove(KEY_TOKEN); it.remove(KEY_REFRESH) }
    }

    /** 记住密码：保存邮箱与密码（调试 App 取折中，明文存于 DataStore） */
    suspend fun saveCredentials(email: String, password: String) {
        ctx.dataStore.edit { it[KEY_EMAIL] = email; it[KEY_PASSWORD] = password; it[KEY_REMEMBER] = "1" }
    }

    /** 清除记住的密码 */
    suspend fun clearCredentials() {
        ctx.dataStore.edit { it.remove(KEY_EMAIL); it.remove(KEY_PASSWORD); it.remove(KEY_REMEMBER) }
    }

    /** 读取已记住的账号密码；无则返回 null */
    suspend fun getCredentials(): Pair<String, String>? {
        val d = ctx.dataStore.data.first()
        val e = d[KEY_EMAIL]; val p = d[KEY_PASSWORD]
        return if (!e.isNullOrBlank() && !p.isNullOrBlank()) e to p else null
    }
}
