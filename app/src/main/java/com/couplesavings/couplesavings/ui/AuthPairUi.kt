package com.couplesavings.couplesavings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.couplesavings.couplesavings.data.ApiService
import com.couplesavings.couplesavings.data.Profile
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
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doAuth(block: suspend () -> Unit) {
        busy = true; msg = ""
        scope.launch {
            runCatching { block() }
                .onSuccess { runCatching { onAuth(ApiService.myProfile()) } }
                .onFailure { msg = it.message ?: "操作失败" }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("情侣存款", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            email, { email = it }, label = { Text("邮箱") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pwd, { pwd = it }, label = { Text("密码（至少 6 位）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { doAuth { ApiService.signUp(email.trim(), pwd) } },
            modifier = Modifier.fillMaxWidth(), enabled = !busy
        ) { Text("注册") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { doAuth { ApiService.signIn(email.trim(), pwd) } },
            modifier = Modifier.fillMaxWidth(), enabled = !busy
        ) { Text("登录") }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun PairScreen(profile: Profile?, onPaired: (Profile) -> Unit) {
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("绑定情侣", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("你的邀请码（发给对方）：", style = MaterialTheme.typography.bodyLarge)
        Text(profile?.invite_code ?: "—", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            code, { code = it }, label = { Text("输入对方的邀请码") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.error)
        }
    }
}
