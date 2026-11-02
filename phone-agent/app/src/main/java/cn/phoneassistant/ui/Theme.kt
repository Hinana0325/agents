package cn.phoneassistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 中性灰白主题（与系统应用套件同一组色值）。
 * 只用灰、白、深灰作主色；状态色仅在有含义处使用：
 * 绿 = 成功，红 = 失败，灰 = 停用。不使用暖色系。
 */
val SuccessGreen = Color(0xFF4C9A6B)
val DangerRed = Color(0xFFC15B5B)
val MutedGrey = Color(0xFF8A8A8A)

val ShapeCard = RoundedCornerShape(16.dp)
val ShapeBubble = RoundedCornerShape(16.dp)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC9CDD3),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color(0xFF1A1A1A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFF9AA0A6),
    error = DangerRed,
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF3A3A3A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3F4756),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E1E1),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF6B7280),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFF6B7280),
    error = DangerRed,
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFD9D9D9),
)

@Composable
fun PhoneAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
            bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        ),
        content = content,
    )
}
