package com.bestspace.vault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** สีเดียวกับแอปคอม */
object Palette {
    val Bg = Color(0xFF14161C)
    val Panel = Color(0xFF1C1F27)
    val Panel2 = Color(0xFF252935)
    val Fg = Color(0xFFE8EAF0)
    val Muted = Color(0xFF8A90A2)
    val Accent = Color(0xFF5B8CFF)
    val Danger = Color(0xFFFF5B6E)
    val Ok = Color(0xFF3ECF8E)
    val Warn = Color(0xFFFFC060)
    val Folder = Color(0xFFFFC75F)
    val ViewBg = Color(0xFF0B0C10)
    val FgInt = 0xFFE8EAF0.toInt()
    val MutedInt = 0xFF8A90A2.toInt()
}

@Composable
fun BestSpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent, onPrimary = Color.White,
            background = Palette.Bg, onBackground = Palette.Fg,
            surface = Palette.Panel, onSurface = Palette.Fg,
            surfaceVariant = Palette.Panel2, onSurfaceVariant = Palette.Muted,
            surfaceContainer = Palette.Panel, surfaceContainerHigh = Palette.Panel2,
            secondaryContainer = Color(0xFF2E3A5C), onSecondaryContainer = Palette.Fg,
            error = Palette.Danger,
        ),
        content = content,
    )
}
