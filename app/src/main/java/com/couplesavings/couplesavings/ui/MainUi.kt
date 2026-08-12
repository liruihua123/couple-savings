@file:OptIn(ExperimentalMaterial3Api::class)

package com.couplesavings.couplesavings.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.couplesavings.couplesavings.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * [INPUT]: 依赖 ApiService / GoldPrice / 数据模型
 * [OUTPUT]: 对外提供 MainScreen 与三个业务屏（总览/资产/流水）+ 增删对话框 + 收益折线图
 * [POS]: ui 层主躯干，持有账户/流水/快照状态，每 8 秒轮询刷新并自动记录当日资产快照
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */

// 中国习惯：涨=红，跌=绿
private fun profitColor(v: Double): Color =
    if (v >= 0) Color(0xFFD32F2F) else Color(0xFF388E3C)

private fun yuan(v: Double): String = "¥%,.2f".format(v)
private fun gram(v: Double): String = "%.2f 克".format(v)

private fun fmtTime(iso: String?): String {
    if (iso == null) return ""
    return try {
        iso.replace("T", " ").take(19)
    } catch (_: Exception) { iso }
}

@Composable
fun MainScreen(profile: Profile?, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var txns by remember { mutableStateOf<List<TransactionRow>>(emptyList()) }
    var snapshots by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var goldPrice by remember { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() = scope.launch {
        val gold = runCatching { GoldPrice.cnyPerGram() }.getOrNull()
        val a = runCatching { ApiService.listAccounts() }
        val t = runCatching { ApiService.listTransactions() }
        // 续期彻底失败（refresh_token 也失效）→ 弹回登录页
        if (a.exceptionOrNull() is ApiService.RefreshFailedException ||
            t.exceptionOrNull() is ApiService.RefreshFailedException
        ) {
            onLogout()
            return@launch
        }
        val accs = a.getOrNull() ?: accounts
        val txnList = t.getOrNull() ?: txns
        accounts = accs
        txns = txnList
        goldPrice = gold ?: goldPrice

        // 记录当日资产快照（折线图历史数据源）——金价拿到才写，避免写入脏数据
        if (gold != null) {
            runCatching { ApiService.upsertSnapshot(computeSnapshot(accs, gold)) }
        }
        runCatching { ApiService.listSnapshots() }.getOrNull()?.let { snapshots = it }
    }

    LaunchedEffect(Unit) { refresh() }
    // 每 8 秒轮询一次，任一方改动另一方自动看到
    LaunchedEffect(Unit) {
        while (true) { delay(8000); refresh() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("情侣存款") },
                actions = {
                    IconButton(onClick = onLogout) { Icon(Icons.Filled.Logout, "退出登录") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, "") }, label = { Text("总览") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.AccountBalance, "") }, label = { Text("资产") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.List, "") }, label = { Text("流水") })
            }
        }
    ) { padding ->
        when (tab) {
            0 -> DashboardScreen(Modifier.padding(padding), profile, accounts, txns, goldPrice, snapshots)
            1 -> AccountsScreen(Modifier.padding(padding), accounts) { refresh() }
            2 -> TransactionsScreen(Modifier.padding(padding), txns) { refresh() }
        }
    }
}

/** 根据当前账户 + 实时金价，算出当日快照（不含 id/couple_id，由后端默认值与 RLS 填充） */
private fun computeSnapshot(accounts: List<Account>, goldPerGram: Double): Snapshot {
    val deposits = accounts.filter { it.type == "deposit" }.sumOf { it.balance }
    val wealth = accounts.filter { it.type == "wealth" }.sumOf { it.balance }
    val goldAccs = accounts.filter { it.type == "gold" }
    val goldGrams = goldAccs.sumOf { it.balance }
    val goldCost = goldAccs.sumOf { it.principal }
    val goldValue = goldGrams * goldPerGram
    val goldProfit = goldValue - goldCost
    val netWorth = deposits + wealth + goldValue
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    return Snapshot(
        snapshot_date = date,
        net_worth = netWorth,
        deposit_total = deposits,
        wealth_total = wealth,
        gold_grams = goldGrams,
        gold_value = goldValue,
        gold_profit = goldProfit
    )
}

// ---------------------- 总览 ----------------------
@Composable
fun DashboardScreen(
    modifier: Modifier,
    profile: Profile?,
    accounts: List<Account>,
    txns: List<TransactionRow>,
    goldPrice: Double?,
    snapshots: List<Snapshot>
) {
    var metric by remember { mutableStateOf("net_worth") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val deposits = accounts.filter { it.type == "deposit" }.sumOf { it.balance }
    val wealth = accounts.filter { it.type == "wealth" }.sumOf { it.balance }
    val goldAccs = accounts.filter { it.type == "gold" }
    val goldGrams = goldAccs.sumOf { it.balance }
    val goldCost = goldAccs.sumOf { it.principal }
    val goldValue = goldGrams * (goldPrice ?: 0.0)
    val goldProfit = goldValue - goldCost
    val netWorth = deposits + wealth + goldValue

    // 折线图数据：取最近 30 天快照（显式类型，避免推断失败级联报错）
    val series: List<Pair<String, Double>> = snapshots
        .takeLast(30)
        .map { s: Snapshot ->
            val v: Double = if (metric == "net_worth") s.net_worth else s.gold_profit
            Pair(s.snapshot_date ?: "", v)
        }
    val up = (series.lastOrNull()?.second ?: 0.0) >= (series.firstOrNull()?.second ?: 0.0)
    val lineColor = if (up) Color(0xFFD32F2F) else Color(0xFF388E3C)
    val latestVal = series.lastOrNull()?.second ?: if (metric == "net_worth") netWorth else goldProfit

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 持久邀请码卡片：配对后也一直能查到，避免"用一次就找不到"
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("我的邀请码（发给对方完成配对）", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            profile?.invite_code ?: "—",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        IconButton(onClick = {
                            profile?.invite_code?.let {
                                clipboard.setText(AnnotatedString(it))
                                copied = true
                            }
                        }) {
                            Icon(Icons.Filled.ContentCopy, "复制邀请码")
                        }
                    }
                    Text(
                        if (copied) "已复制到剪贴板" else "点右侧图标即可复制；配对完成后这里也一直能查到",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Text("共同净资产", style = MaterialTheme.typography.titleMedium)
                    Text(yuan(netWorth), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(Modifier.weight(1f), "共同存款", yuan(deposits))
                StatCard(Modifier.weight(1f), "理财净值", yuan(wealth))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("积存金", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("持有", style = MaterialTheme.typography.bodyMedium)
                        Text(gram(goldGrams), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("实时市值", style = MaterialTheme.typography.bodyMedium)
                        Text(yuan(goldValue), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("浮动盈亏", style = MaterialTheme.typography.bodyMedium)
                        Text("${yuan(goldProfit)} (${"%.2f".format(goldProfit / (goldCost.takeIf { it > 0 } ?: 1.0) * 100)}%)",
                            color = profitColor(goldProfit))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前金价：${goldPrice?.let { "¥%,.2f/克".format(it) } ?: "加载中…"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        // ---- 收益走势折线图 ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("收益走势", style = MaterialTheme.typography.titleMedium)
                        Text(yuan(latestVal), color = lineColor, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = metric == "net_worth", onClick = { metric = "net_worth" }, label = { Text("净资产") })
                        FilterChip(selected = metric == "gold_profit", onClick = { metric = "gold_profit" }, label = { Text("积存金盈亏") })
                    }
                    Spacer(Modifier.height(8.dp))
                    TrendChart(Modifier.fillMaxWidth(), series, lineColor)
                }
            }
        }
        item { Text("最近流水", style = MaterialTheme.typography.titleMedium) }
        items(txns.take(10)) { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("${if (t.type == "income") "收入" else "支出"} · ${t.category}", style = MaterialTheme.typography.bodyMedium)
                    Text(t.note.ifBlank { t.created_by_name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Text((if (t.type == "income") "+" else "-") + yuan(t.amount),
                    color = profitColor(if (t.type == "income") 1.0 else -1.0))
            }
        }
    }
}

/** 手写 Canvas 折线图，零额外依赖；数据点 <2 时提示"数据积累中" */
@Composable
private fun TrendChart(
    modifier: Modifier,
    points: List<Pair<String, Double>>,
    lineColor: Color
) {
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            Text("数据积累中：每天自动记录一次，几天后就有曲线了", color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    val min = points.minOf { it.second }
    val max = points.maxOf { it.second }
    val range = if (max - min == 0.0) 1.0 else max - min
    Canvas(modifier.fillMaxWidth().height(160.dp).padding(8.dp)) {
        val w = size.width
        val h = size.height
        val pad = 8.dp.toPx()
        val usableW = w - pad * 2
        val usableH = h - pad * 2
        val stepX = if (points.size > 1) usableW / (points.size - 1) else 0f
        // 坐标一律 Float，toY 显式 toFloat 避免 Double/Float 混用导致 Offset 构造失败
        val toX: (Int) -> Float = { i -> pad + i * stepX }
        val toY: (Double) -> Float = { v -> (pad + usableH - ((v - min) / range).toFloat() * usableH) }

        drawLine(Color.LightGray, Offset(pad, h - pad), Offset(w - pad, h - pad), strokeWidth = 1f)

        val fill = Path().apply {
            moveTo(toX(0), toY(points.first().second))
            points.forEachIndexed { i, p -> lineTo(toX(i), toY(p.second)) }
            lineTo(toX(points.lastIndex), h - pad)
            lineTo(toX(0), h - pad)
            close()
        }
        drawPath(fill, lineColor.copy(alpha = 0.12f))

        val line = Path().apply {
            moveTo(toX(0), toY(points.first().second))
            points.forEachIndexed { i, p -> lineTo(toX(i), toY(p.second)) }
        }
        drawPath(line, lineColor, style = Stroke(width = 2.5f))

        drawCircle(lineColor, 3.dp.toPx(), Offset(toX(points.lastIndex), toY(points.last().second)))
    }
}

@Composable
private fun StatCard(modifier: Modifier, title: String, value: String) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

// ---------------------- 资产 ----------------------
@Composable
fun AccountsScreen(modifier: Modifier, accounts: List<Account>, onChange: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Button(onClick = { showDialog = true }, Modifier.fillMaxWidth()) { Text("+ 添加账户") }
        }
        items(accounts) { a ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (a.type) {
                                "deposit" -> "存款 · ${yuan(a.balance)}"
                                "wealth" -> "理财 · 净值 ${yuan(a.balance)}（本金 ${yuan(a.principal)}）"
                                "gold" -> "积存金 · ${gram(a.balance)}（成本 ${yuan(a.principal)}）"
                                else -> a.type
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = {
                        scope.launch { runCatching { ApiService.deleteAccount(a.id!!) }; onChange() }
                    }) { Icon(Icons.Filled.Delete, "删除") }
                }
            }
        }
    }

    if (showDialog) {
        AddAccountDialog(
            onDismiss = { showDialog = false },
            onConfirm = { acc ->
                scope.launch { runCatching { ApiService.insertAccount(acc) }; onChange() }
                showDialog = false
            }
        )
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onConfirm: (Account) -> Unit) {
    var type by remember { mutableStateOf("deposit") }
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var principal by remember { mutableStateOf("") }

    val typeLabel = when (type) {
        "deposit" -> "余额（元）"
        "wealth" -> "当前净值（元）"
        "gold" -> "持有克数（克）"
        else -> "数值"
    }
    val principalLabel = when (type) {
        "deposit" -> "（存款无需填）"
        "wealth" -> "投入本金（元）"
        "gold" -> "累计投入成本（元）"
        else -> "本金/成本"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            val b = balance.toDoubleOrNull() ?: 0.0
            val p = principal.toDoubleOrNull() ?: 0.0
            onConfirm(Account(type = type, name = name.ifBlank { typeLabel }, balance = b, principal = p))
        }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text("添加账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmented(type) { type = it }
                OutlinedTextField(name, { name = it }, label = { Text("名称（如：余额宝 / 工行积存金）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(balance, { balance = it }, label = { Text(typeLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(principal, { principal = it }, label = { Text(principalLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    )
}

@Composable
private fun SingleChoiceSegmented(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("deposit" to "存款", "wealth" to "理财", "gold" to "积存金")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

// ---------------------- 流水 ----------------------
@Composable
fun TransactionsScreen(modifier: Modifier, txns: List<TransactionRow>, onChange: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = { showDialog = true }, Modifier.fillMaxWidth()) { Text("+ 记一笔") } }
        items(txns) { t ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${if (t.type == "income") "收入" else "支出"} · ${t.category}", style = MaterialTheme.typography.titleMedium)
                        Text("${t.note.ifBlank { "—" }} · ${t.created_by_name} · ${fmtTime(t.created_at)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text((if (t.type == "income") "+" else "-") + yuan(t.amount),
                        color = profitColor(if (t.type == "income") 1.0 else -1.0))
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        scope.launch { runCatching { ApiService.deleteTransaction(t.id!!) }; onChange() }
                    }) { Icon(Icons.Filled.Delete, "删除") }
                }
            }
        }
    }

    if (showDialog) {
        AddTxnDialog(
            onDismiss = { showDialog = false },
            onConfirm = { t ->
                scope.launch { runCatching { ApiService.insertTransaction(t) }; onChange() }
                showDialog = false
            }
        )
    }
}

@Composable
private fun AddTxnDialog(onDismiss: () -> Unit, onConfirm: (TransactionRow) -> Unit) {
    var type by remember { mutableStateOf("expense") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            val amt = amount.toDoubleOrNull() ?: 0.0
            onConfirm(
                TransactionRow(
                    type = type, amount = amt,
                    category = category.ifBlank { "其他" },
                    note = note, created_by_name = who.ifBlank { "我" }
                )
            )
        }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text("记一笔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "expense", onClick = { type = "expense" }, label = { Text("支出") })
                    FilterChip(selected = type == "income", onClick = { type = "income" }, label = { Text("收入") })
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it }, label = { Text("分类（如：餐饮/工资）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("备注") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(who, { who = it }, label = { Text("谁记的（如：我/对象）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    )
}
