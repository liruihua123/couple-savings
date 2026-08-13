@file:OptIn(ExperimentalMaterial3Api::class)

package com.couplesavings.couplesavings.ui

import android.graphics.Color as AndroidColor
import android.widget.Toast
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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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

// 金价缓存：60 秒内复用，避免 8 秒轮询无脑打外部行情接口（弱网下会拖垮体验）
private var cachedGold: Double? = null
private var cachedGoldAt = 0L
private suspend fun fetchGold(): Double? {
    val now = System.currentTimeMillis()
    if (cachedGold != null && now - cachedGoldAt < 60_000) return cachedGold
    return runCatching { GoldPrice.cnyPerGram() }.getOrNull().also {
        if (it != null) { cachedGold = it; cachedGoldAt = now }
    }
}

@Composable
fun MainScreen(profile: Profile?, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var txns by remember { mutableStateOf<List<TransactionRow>>(emptyList()) }
    var snapshots by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var goldPrice by remember { mutableStateOf<Double?>(null) }
    var profileState by remember { mutableStateOf(profile) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()

    fun refresh() = scope.launch {
        val gold = fetchGold()
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
        // 先拉快照，再决定是否落盘，避免每轮轮询都写库
        val snaps = runCatching { ApiService.listSnapshots() }.getOrNull() ?: snapshots
        accounts = accs
        txns = txnList
        goldPrice = gold ?: goldPrice
        snapshots = snaps

        // 每日只记录一次资产快照（折线图历史数据源），8 秒轮询不再无脑写库
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (gold != null && snaps.none { it.snapshot_date == today }) {
            runCatching { ApiService.upsertSnapshot(computeSnapshot(accs, gold)) }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // 每 8 秒轮询一次，任一方改动另一方自动看到
    LaunchedEffect(Unit) {
        while (true) { delay(8000); refresh() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("情侣攒钱") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "刷新") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "设置") }
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
            0 -> DashboardScreen(Modifier.padding(padding), profileState, accounts, txns, goldPrice, snapshots)
            1 -> AccountsScreen(Modifier.padding(padding), accounts, { refresh() }, myId = profileState?.id)
            2 -> TransactionsScreen(Modifier.padding(padding), profileState, accounts, txns) { refresh() }
        }
    }
    if (showSettings) {
        SettingsDialog(
            initialName = profileState?.display_name ?: "",
            onDismiss = { showSettings = false },
            onSave = { name ->
                scope.launch {
                    runCatching { ApiService.updateProfileName(name) }
                        .onFailure { toast("保存失败：" + (it.message ?: "")) }
                        .onSuccess { profileState = profileState?.copy(display_name = name) }
                }
                showSettings = false
            }
        )
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
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF0E9C8A), Color(0xFF36C7B4))),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(22.dp)
            ) {
                Column {
                    Text("共同净资产", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(yuan(netWorth), color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NetChip("存款", yuan(deposits))
                        NetChip("理财", yuan(wealth))
                        NetChip("积存金", yuan(goldValue))
                    }
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
        // ---- 本月收支 ----
        item {
            val ym = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
            val monthTxns = txns.filter { it.created_at?.take(7) == ym }
            val monthIncome = monthTxns.filter { it.type == "income" }.sumOf { it.amount }
            val monthExpense = monthTxns.filter { it.type == "expense" }.sumOf { it.amount }
            val monthNet = monthIncome - monthExpense
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("本月收支（$ym）", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("收入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            Text("+${yuan(monthIncome)}", color = profitColor(1.0), style = MaterialTheme.typography.titleMedium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("支出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            Text("-${yuan(monthExpense)}", color = profitColor(-1.0), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("本月净攒", style = MaterialTheme.typography.bodyMedium)
                        Text(yuan(monthNet), color = profitColor(monthNet), style = MaterialTheme.typography.titleLarge)
                    }
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

/** 净资产 Hero 卡上的半透明分项标签 */
@Composable
private fun NetChip(label: String, value: String) {
    Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
            Text(value, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ---------------------- 设置（昵称） ----------------------
@Composable
private fun SettingsDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifBlank { "我" }) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text("个人设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "设置你的昵称，记账时「谁记的」会自动带入，对方也能看到是谁记的。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("我的昵称（如：我 / 老公 / 小美）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ---------------------- 资产 ----------------------
@Composable
fun AccountsScreen(modifier: Modifier, accounts: List<Account>, onChange: () -> Unit, myId: String? = null) {
    var showDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingDel by remember { mutableStateOf<Account?>(null) }
    var editAccount by remember { mutableStateOf<Account?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Button(onClick = { showDialog = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "处理中…" else "+ 添加账户") }
        }
        if (accounts.isEmpty()) {
            item { Text("还没有账户，点上方「添加账户」记下你的存款 / 理财 / 积存金吧～", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline) }
        }
        items(accounts) { a ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (ic, tint) = when (a.type) {
                        "deposit" -> Icons.Filled.AccountBalanceWallet to Color(0xFF0E9C8A)
                        "wealth" -> Icons.Filled.TrendingUp to Color(0xFF7B61FF)
                        "gold" -> Icons.Filled.Star to Color(0xFFD4A017)
                        else -> Icons.Filled.Circle to Color.Gray
                    }
                    Box(
                        Modifier.size(44.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(ic, "", tint = tint) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name + if (myId != null && a.owner_id != null && a.owner_id != myId) "（对方）" else "", style = MaterialTheme.typography.titleMedium)
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
                    IconButton(onClick = { editAccount = a }, enabled = !busy) { Icon(Icons.Filled.Edit, "编辑") }
                    IconButton(onClick = { pendingDel = a }, enabled = !busy) { Icon(Icons.Filled.Delete, "删除") }
                }
            }
        }
    }

    if (showDialog || editAccount != null) {
        val editing = editAccount
        AddAccountDialog(
            initial = editing,
            busy = busy,
            onDismiss = { showDialog = false; editAccount = null },
            onConfirm = { acc ->
                scope.launch {
                    busy = true
                    val err = if (editing != null)
                        runCatching { ApiService.updateAccount(acc.copy(id = editing.id)) }.exceptionOrNull()?.message
                    else
                        runCatching { ApiService.insertAccount(acc) }.exceptionOrNull()?.message
                    busy = false
                    showDialog = false; editAccount = null
                    if (err != null) toast("保存失败：$err") else { toast(if (editing != null) "已更新 ✓" else "已添加 ✓"); onChange() }
                }
            }
        )
    }

    pendingDel?.let { del ->
        AlertDialog(
            onDismissRequest = { pendingDel = null },
            confirmButton = { TextButton(onClick = {
                pendingDel = null
                scope.launch {
                    busy = true
                    val err = runCatching { ApiService.deleteAccount(del.id!!) }.exceptionOrNull()?.message
                    busy = false
                    if (err != null) toast("删除失败：$err") else { toast("已删除"); onChange() }
                }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDel = null }) { Text("取消") } },
            title = { Text("确认删除该账户？") },
            text = { Text("删除「${del.name}」后，关联的流水仍保留但不再计入净资产。") }
        )
    }
}

@Composable
private fun AddAccountDialog(initial: Account? = null, busy: Boolean, onDismiss: () -> Unit, onConfirm: (Account) -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: "deposit") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var balance by remember { mutableStateOf(if (initial != null && initial.balance != 0.0) initial.balance.toString() else "") }
    var principal by remember { mutableStateOf(if (initial != null && initial.principal != 0.0) initial.principal.toString() else "") }

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
    val prinNeeded = type != "deposit"
    val b = balance.toDoubleOrNull()
    val p = principal.toDoubleOrNull()
    val balValid = b != null && b >= 0 && b.isFinite()
    val prinValid = if (!prinNeeded) true else (p != null && p >= 0 && p.isFinite())
    val nameValid = name.isNotBlank()
    val showBalErr = balance.isNotBlank() && !balValid
    val showPrinErr = prinNeeded && principal.isNotBlank() && !prinValid
    val valid = nameValid && balValid && prinValid

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (!valid) return@TextButton
                onConfirm(Account(type = type, name = name.trim(), balance = b!!, principal = if (prinNeeded) p!! else 0.0))
            }, enabled = !busy && valid) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text(if (initial != null) "编辑账户" else "添加账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmented(type) { type = it }
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("名称（如：余额宝 / 工行积存金）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = name.isBlank(),
                    supportingText = { if (name.isBlank()) Text("请输入账户名称") }
                )
                OutlinedTextField(
                    balance, { balance = it },
                    label = { Text(typeLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = showBalErr,
                    supportingText = { if (showBalErr) Text("请输入不小于 0 的数字") }
                )
                OutlinedTextField(
                    principal, { principal = it },
                    label = { Text(principalLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = showPrinErr,
                    supportingText = { if (showPrinErr) Text("请输入不小于 0 的数字") }
                )
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
fun TransactionsScreen(modifier: Modifier, profile: Profile?, accounts: List<Account>, txns: List<TransactionRow>, onChange: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingDel by remember { mutableStateOf<TransactionRow?>(null) }
    var editTarget by remember { mutableStateOf<TransactionRow?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
    val defaultWho = profile?.display_name ?: "我"

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = { showDialog = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "处理中…" else "+ 记一笔") } }
        if (txns.isEmpty()) {
            item { Text("还没有流水，点上方「记一笔」开始记录吧～", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline) }
        }
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
                    IconButton(onClick = { editTarget = t }, enabled = !busy) { Icon(Icons.Filled.Edit, "编辑") }
                    IconButton(onClick = { pendingDel = t }, enabled = !busy) { Icon(Icons.Filled.Delete, "删除") }
                }
            }
        }
    }

    if (showDialog || editTarget != null) {
        val editing = editTarget
        AddTxnDialog(
            initial = editing,
            defaultWho = defaultWho,
            busy = busy,
            accounts = accounts,
            myId = profile?.id,
            onDismiss = { showDialog = false; editTarget = null },
            onConfirm = { t ->
                scope.launch {
                    busy = true
                    val err = if (editing != null)
                        runCatching { ApiService.updateTransaction(editing.id!!, t) }.exceptionOrNull()?.message
                    else
                        runCatching { ApiService.insertTransaction(t) }.exceptionOrNull()?.message
                    busy = false
                    showDialog = false; editTarget = null
                    if (err != null) toast("保存失败：$err") else { toast(if (editing != null) "已更新 ✓" else "已记录 ✓"); onChange() }
                }
            }
        )
    }

    pendingDel?.let { del ->
        AlertDialog(
            onDismissRequest = { pendingDel = null },
            confirmButton = { TextButton(onClick = {
                pendingDel = null
                scope.launch {
                    busy = true
                    val err = runCatching { ApiService.deleteTransaction(del.id!!) }.exceptionOrNull()?.message
                    busy = false
                    if (err != null) toast("删除失败：$err") else { toast("已删除"); onChange() }
                }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDel = null }) { Text("取消") } },
            title = { Text("确认删除这笔流水？") },
            text = { Text("「${if (del.type == "income") "收入" else "支出"} · ${del.category} · ${yuan(del.amount)}」删除后关联账户余额会自动冲回。") }
        )
    }
}

@Composable
private fun AddTxnDialog(
    initial: TransactionRow? = null,
    defaultWho: String,
    busy: Boolean,
    accounts: List<Account>,
    myId: String?,
    onDismiss: () -> Unit,
    onConfirm: (TransactionRow) -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: "expense") }
    var amount by remember { mutableStateOf(if (initial != null && initial.amount != 0.0) initial.amount.toString() else "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var who by remember { mutableStateOf(initial?.created_by_name?.takeIf { it.isNotBlank() } ?: defaultWho) }
    // 仅本人、且非积存金（克）的账户可关联，避免用元金额去动克数
    val eligible = remember(accounts, myId) { accounts.filter { it.type != "gold" && it.owner_id == myId } }
    var selAccountId by remember(eligible, initial) {
        mutableStateOf(initial?.account_id ?: eligible.firstOrNull()?.id)
    }

    val amt = amount.toDoubleOrNull()
    val amountValid = amt != null && amt > 0 && amt.isFinite()
    val showAmountErr = amount.isNotBlank() && !amountValid
    val cats = if (type == "income")
        listOf("工资", "红包", "理财收益", "兼职", "其他")
    else
        listOf("餐饮", "交通", "购物", "居家", "医疗", "娱乐", "其他")

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (!amountValid) return@TextButton
                onConfirm(
                    TransactionRow(
                        type = type, amount = amt!!,
                        category = category.ifBlank { "其他" },
                        note = note, created_by_name = who.ifBlank { "我" },
                        account_id = selAccountId
                    )
                )
            }, enabled = !busy && amountValid) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text(if (initial != null) "编辑这笔流水" else "记一笔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "expense", onClick = { type = "expense" }, label = { Text("支出") })
                    FilterChip(selected = type == "income", onClick = { type = "income" }, label = { Text("收入") })
                }
                OutlinedTextField(
                    amount, { amount = it },
                    label = { Text("金额（元）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = showAmountErr,
                    supportingText = { if (showAmountErr) Text("请输入大于 0 的金额") }
                )
                OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("备注") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(who, { who = it }, label = { Text("谁记的（如：我/对象）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("关联账户（记账后自动加减余额）：", style = MaterialTheme.typography.bodyMedium)
                if (eligible.isEmpty()) {
                    Text("你还没有可关联的存款/理财账户，本次仅记录流水。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = selAccountId == null, onClick = { selAccountId = null }, label = { Text("不关联") })
                        eligible.forEach { acc ->
                            FilterChip(selected = selAccountId == acc.id, onClick = { selAccountId = acc.id }, label = { Text(acc.name) })
                        }
                    }
                }
            }
        }
    )
}
