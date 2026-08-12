package com.couplesavings.couplesavings.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [INPUT]: 依赖 httpRequest 拉取 XAU 现货价与 USD/CNY 汇率
 * [OUTPUT]: 对外提供 cnyPerGram() —— 当前黄金价格(元/克)
 * [POS]: data 层的行情原语，供积存金实时市值与浮动盈亏计算使用
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 *
 * 数据源：api.gold-api.com(XAU 美元/盎司) + open.er-api.com(USD→CNY)
 * 换算：元/克 = (XAU美元/盎司 × 汇率) / 31.1034768(克/盎司)
 * 这是国际现货金价估算，与各家银行"积存金"报价会有点差，属正常。
 *
 * 健壮性：两个外部接口都不保证永远可用，故全程安全解析（不依赖 !!），
 * 任一接口失败时整体抛异常，由上层 runCatching 兜底（金价留空、界面显示"加载中"），
 * 不再用 NPE 把整屏带崩；汇率接口不可达时退回到近一年中枢值作兜底，避免积存金恒为 0。
 */
object GoldPrice {
    private val json = Json { ignoreUnknownKeys = true }

    /** USD→CNY 兜底汇率（仅在汇率接口不可达时使用，仅作保底，非实时） */
    private const val FALLBACK_USD_CNY = 7.15

    suspend fun cnyPerGram(): Double {
        val xauText = httpRequest("GET", "https://api.gold-api.com/price/XAU")
        val xauUsd = json.parseToJsonElement(xauText)
            .jsonObject["price"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: throw ApiException(0, "金价接口返回异常：未包含 price 字段")

        val fxText = runCatching {
            httpRequest("GET", "https://open.er-api.com/v6/latest/USD")
        }.getOrDefault("")
        val cny = if (fxText.isNotBlank()) {
            json.parseToJsonElement(fxText)
                .jsonObject["rates"]?.jsonObject?.get("CNY")?.jsonPrimitive?.content?.toDoubleOrNull()
        } else null
        val rate = cny ?: FALLBACK_USD_CNY

        return (xauUsd * rate) / 31.1034768
    }
}
