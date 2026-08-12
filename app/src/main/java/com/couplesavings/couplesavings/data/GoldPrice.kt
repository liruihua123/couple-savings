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
 * 数据源：gold-api.com(XAU 美元/盎司) + exchangerate.host(USD→CNY)
 * 换算：元/克 = (XAU美元/盎司 × 汇率) / 31.1034768(克/盎司)
 * 这是国际现货金价估算，与各家银行"积存金"报价会有点差，属正常。
 */
object GoldPrice {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun cnyPerGram(): Double {
        val xauText = httpRequest("GET", "https://api.gold-api.com/price/XAU")
        val xauUsd = json.parseToJsonElement(xauText)
            .jsonObject["price"]!!.jsonPrimitive.content.toDouble()

        val fxText = httpRequest("GET", "https://api.exchangerate.host/latest?base=USD&symbols=CNY")
        val cny = json.parseToJsonElement(fxText)
            .jsonObject["rates"]!!.jsonObject["CNY"]!!.jsonPrimitive.content.toDouble()

        return (xauUsd * cny) / 31.1034768
    }
}
