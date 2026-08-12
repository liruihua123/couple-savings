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
    const val URL = "https://tmhxxodvqhvbpzfmmsfx.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRtaHh4b2R2cWh2YnB6Zm1tc2Z4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY1MTIzMTUsImV4cCI6MjEwMjA4ODMxNX0.Oxw-47JrE6c4BTRa3IK9btD1psCysn8lQzXOt966Dic"
}
