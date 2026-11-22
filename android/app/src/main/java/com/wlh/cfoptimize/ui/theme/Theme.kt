package com.wlh.cfoptimize.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF5DCAA5),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFF9FE1CB),
    secondary = Color(0xFF85B7EB),
    onSecondary = Color(0xFF042C53),
    secondaryContainer = Color(0xFF0C447C),
    onSecondaryContainer = Color(0xFFB5D4F4),
    tertiary = Color(0xFFEF9F27),
    onTertiary = Color(0xFF412402),
    background = Color(0xFF0E1412),
    onBackground = Color(0xFFE1E9E4),
    surface = Color(0xFF0E1412),
    onSurface = Color(0xFFE1E9E4),
    surfaceVariant = Color(0xFF2A3431),
    onSurfaceVariant = Color(0xFFB6C2BC),
    outline = Color(0xFF6E7A75)
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF0F6E56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9FE1CB),
    onPrimaryContainer = Color(0xFF04342C),
    secondary = Color(0xFF185FA5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB5D4F4),
    onSecondaryContainer = Color(0xFF042C53),
    tertiary = Color(0xFF854F0B),
    onTertiary = Color.White,
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5E0),
    onSurfaceVariant = Color(0xFF404944),
    outline = Color(0xFF707974)
)

/** 等宽 + tabular 数字，避免时延刷新时宽度跳动。 */
val NumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum"
)

@Composable
fun CfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
