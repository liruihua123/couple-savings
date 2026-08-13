package com.couplesavings.couplesavings.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 清新蓝绿主题：金融科技感，干净现代；绿涨红跌已在业务层适配
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E9C8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F0E4),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color.White,
    tertiary = Color(0xFF2E7D92),
    background = Color(0xFFF5FBFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE4F2EF),
    outline = Color(0xFFB5CCC7),
    error = Color(0xFFD32F2F),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4DD0C0),
    onPrimary = Color(0xFF00332B),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFF9FF2E2),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332B),
    tertiary = Color(0xFF7FB9CC),
    background = Color(0xFF0E1A18),
    surface = Color(0xFF13211F),
    surfaceVariant = Color(0xFF29403C),
    outline = Color(0xFF4E6A65),
    error = Color(0xFFEF9A9A),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = AppShapes,
        content = content
    )
}
