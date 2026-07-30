package com.paladex.ex.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Pokémon logo yellow and blue, on the near-black the scanner needs. */
val Accent = Color(0xFFFFCB05)
val AccentDeep = Color(0xFF3B6CB8)
val Ink = Color(0xFF0B0D13)
val Surface1 = Color(0xFF141824)
val Surface2 = Color(0xFF1D2231)
val Line = Color(0xFF2A3145)
val Muted = Color(0xFF8A93AB)
val Good = Color(0xFF4ADE80)
val Bad = Color(0xFFF87171)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1A1A),
    secondary = AccentDeep,
    background = Ink,
    onBackground = Color(0xFFEEF1F8),
    surface = Surface1,
    onSurface = Color(0xFFEEF1F8),
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Line,
    error = Bad,
)

// The camera viewfinder is the centre of this app and reads badly on white, so
// the light scheme is the dark one — deliberately, not by omission.
private val LightColors = DarkColors

@Composable
fun PaladexTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
