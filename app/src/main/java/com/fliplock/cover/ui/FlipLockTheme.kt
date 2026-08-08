package com.fliplock.cover.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2E5AAC),
    secondary = Color(0xFF4A6572),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CC0FF),
    secondary = Color(0xFFB0C4CE),
)

/** Couleurs d'etat, utilisees pour les pastilles. */
object StatusColors {
    val ok = Color(0xFF1B8A3A)
    val okContainer = Color(0x221B8A3A)
    val warn = Color(0xFFB26A00)
    val warnContainer = Color(0x22B26A00)
    val off = Color(0xFF6B7280)
    val offContainer = Color(0x226B7280)
    val alert = Color(0xFFC62828)
    val alertContainer = Color(0x22C62828)
}

@Composable
fun FlipLockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
