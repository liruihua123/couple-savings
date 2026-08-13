package com.couplesavings.couplesavings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.couplesavings.couplesavings.data.ApiService
import com.couplesavings.couplesavings.data.Profile
import com.couplesavings.couplesavings.data.SessionManager
import kotlinx.coroutines.launch

/**
 * [INPUT]: 依赖 ApiService（认证 / 配对）
 * [OUTPUT]: 对外提供 AuthScreen（登录注册）与 PairScreen（情侣邀请码绑定）
 * [POS]: ui 层的入口与配对流程，登录成功后回调 onAuth 带回来 profile
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
@Composable
fun AuthScreen(onAuth: (Profile) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 启动时自动填入已记住的账号密码
    LaunchedEffect(Unit) {
        SessionManager.getCredentials()?.let { (e, p) -> email = e; pwd = p }
    }

    fun doAuth(block: suspend () -> Unit) {
        busy = true; msg = ""
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    if (remember) SessionManager.saveCredentials(email.trim(), pwd)
                    else SessionManager.clearCredentials()
                    runCatching { onAuth(ApiService.myProfile()) }
                }
                .onFailure { msg = it.message ?: "操作失败" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0E9C8A), Color(0xFF3DD3C0))),
                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                )
                .padding(horizontal = 24.dp, vertical = 44.dp)
        ) {
            Column {
                Text("💰 情侣攒钱", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("一起攒钱，看得见未来", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("登录 / 注册", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    email, { email = it }, label = { Text("邮箱") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    pwd, { pwd = it }, label = { Text("密码（至少 6 位）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { doAuth { ApiService.signUp(email.trim(), pwd) } },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) { Text("注册") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { doAuth { ApiService.signIn(email.trim(), pwd) } },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) { Text("登录") }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("记住密码（下次自动填入）", style = MaterialTheme.typography.bodyMedium)
                }
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun PairScreen(profile: Profile?, onPaired: (Profile) -> Unit) {
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0E9C8A), Color(0xFF3DD3C0))),
                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                )
                .padding(horizontal = 24.dp, vertical = 44.dp)
        ) {
            Column {
                Text("💞 绑定情侣", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("输入对方邀请码，资产同步看", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("你的邀请码（发给对方）：", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(profile?.invite_code ?: "—", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    code, { code = it }, label = { Text("输入对方的邀请码") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        busy = true; msg = ""
                        scope.launch {
                            runCatching { ApiService.pair(code.trim()) }
                                .onSuccess { runCatching { onPaired(ApiService.myProfile()) } }
                                .onFailure { msg = it.message ?: "绑定失败" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                ) { Text("绑定") }
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
