package com.couplesavings.couplesavings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.couplesavings.couplesavings.data.ApiService
import com.couplesavings.couplesavings.data.Profile
import com.couplesavings.couplesavings.data.SessionManager
import com.couplesavings.couplesavings.ui.AuthScreen
import com.couplesavings.couplesavings.ui.MainScreen
import com.couplesavings.couplesavings.ui.PairScreen
import kotlinx.coroutines.launch

/**
 * [INPUT]: 依赖 Compose / ApiService / SessionManager / 各 UI 屏幕
 * [OUTPUT]: 对外提供 App 入口与顶层导航状态机
 * [POS]: 应用根，负责初始化会话、按"未登录→未配对→已配对"三态切换屏幕
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.init(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RootApp()
                }
            }
        }
    }
}

@Composable
private fun RootApp() {
    val scope = rememberCoroutineScope()
    var authed by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf<Boolean?>(null) } // null=校验中
    var profile by remember { mutableStateOf<Profile?>(null) }

    LaunchedEffect(Unit) {
        val token = SessionManager.getToken()
        if (!token.isNullOrBlank()) {
            runCatching { ApiService.myProfile() }
                .onSuccess { profile = it; authed = true; paired = !it.couple_id.isNullOrBlank() }
                .onFailure { SessionManager.clear() }
        }
    }

    when {
        !authed -> AuthScreen(
            onAuth = { p ->
                profile = p
                authed = true
                paired = !p.couple_id.isNullOrBlank()
            }
        )
        authed && paired != true -> PairScreen(
            profile = profile,
            onPaired = { p -> profile = p; paired = true }
        )
        else -> MainScreen(
            profile = profile,
            onLogout = {
                scope.launch { ApiService.signOut() }
                authed = false; paired = null; profile = null
            }
        )
    }
}
