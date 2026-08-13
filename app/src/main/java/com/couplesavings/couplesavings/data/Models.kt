package com.couplesavings.couplesavings.data

import kotlinx.serialization.Serializable

/**
 * [INPUT]: 无（纯数据契约）
 * [OUTPUT]: 对外提供 Profile / Account / TransactionRow / AuthUser / AccountPatch 等序列化模型
 * [POS]: data 层与 Supabase 表一一对应的数据契约，UI 层只认这些类型
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 *
 * accounts.type: deposit(存款) | wealth(理财) | gold(积存金)
 *   - deposit: balance = 余额(元)
 *   - wealth:  balance = 当前净值(元，手动录入)；principal = 投入本金(元)
 *   - gold:    balance = 持有克数；principal = 累计投入成本(元)
 * transactions.type: income(收入) | expense(支出)
 */
@Serializable
data class Profile(
    val id: String,
    val email: String? = null,
    val display_name: String? = null,
    val invite_code: String? = null,
    val couple_id: String? = null
)

@Serializable
data class Account(
    val id: String? = null,
    val couple_id: String? = null,
    val owner_id: String? = null,
    val type: String = "deposit",
    val name: String = "",
    val balance: Double = 0.0,
    val principal: Double = 0.0,
    val created_at: String? = null
)

/** 仅包含可编辑字段，用于 PATCH 更新，避免把 id/owner_id/couple_id 误带上去 */
@Serializable
data class AccountPatch(
    val name: String,
    val type: String,
    val balance: Double,
    val principal: Double
)

/** 流水的可编辑字段（PATCH 用）。不含 id/couple_id/owner_id/created_at，避免覆盖服务端时间戳 */
@Serializable
data class TransactionPatch(
    val type: String,
    val amount: Double,
    val category: String,
    val note: String,
    val created_by_name: String,
    val account_id: String? = null
)

@Serializable
data class TransactionRow(
    val id: String? = null,
    val couple_id: String? = null,
    val owner_id: String? = null,
    val account_id: String? = null,
    val type: String = "expense",
    val amount: Double = 0.0,
    val category: String = "",
    val note: String = "",
    val created_by_name: String = "",
    val created_at: String? = null
)

/** 每日资产快照（折线图历史数据源）。snapshot_date 留空时由数据库默认当天 */
@Serializable
data class Snapshot(
    val id: String? = null,
    val couple_id: String? = null,
    val snapshot_date: String? = null,
    val net_worth: Double = 0.0,
    val deposit_total: Double = 0.0,
    val wealth_total: Double = 0.0,
    val gold_grams: Double = 0.0,
    val gold_value: Double = 0.0,
    val gold_profit: Double = 0.0,
    val created_at: String? = null
)

@Serializable
data class AuthUser(
    val id: String,
    val email: String? = null
)

/**
 * 仅用于 INSERT 的精简 DTO：显式省略 id / created_at 等由数据库生成的字段，
 * 避免 kotlinx 把 null 显式序列化后发给 NOT NULL 列而触发 23502。
 */
@Serializable
data class TransactionInsert(
    val couple_id: String? = null,
    val owner_id: String,
    val account_id: String? = null,
    val type: String,
    val amount: Double,
    val category: String,
    val note: String,
    val created_by_name: String
)

@Serializable
data class AccountInsert(
    val couple_id: String? = null,
    val owner_id: String,
    val type: String,
    val name: String,
    val balance: Double,
    val principal: Double
)

@Serializable
data class SnapshotInsert(
    val couple_id: String? = null,
    val snapshot_date: String? = null,
    val net_worth: Double,
    val deposit_total: Double,
    val wealth_total: Double,
    val gold_grams: Double,
    val gold_value: Double,
    val gold_profit: Double
)
