package com.couplesavings.couplesavings.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * [INPUT]: 依赖标准库 java.net.HttpURLConnection
 * [OUTPUT]: 对外提供 httpRequest() 同步式 HTTP 助手（suspend 包装）
 * [POS]: data 层最底层网络原语，所有 Supabase REST / 金价 API 都走它
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 *
 * 设计取舍：不引入 Retrofit/OkHttp，用标准库直连，减少依赖与编译风险。
 */
class ApiException(val code: Int, val body: String) : Exception("HTTP $code: $body")

suspend fun httpRequest(
    method: String,
    url: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap()
): String = withContext(Dispatchers.IO) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20000
        readTimeout = 20000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        if (body != null) {
            doOutput = true
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.readText().orEmpty()
    conn.disconnect()
    if (code !in 200..299) throw ApiException(code, text)
    text
}
