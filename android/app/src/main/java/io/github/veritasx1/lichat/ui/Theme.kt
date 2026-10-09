package io.github.veritasx1.lichat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.veritasx1.lichat.R

/** Colors and type like Messages on iOS 18 (no Liquid Glass): Apple's system colors, blue bubbles for LiChat, grey for
 *  the others, Inter (close to SF Pro) – the values of ~/Team/LI-GESTALTUNG.md. */
class Palette(val dark: Boolean) {
    val background = if (dark) Color.Black else Color.White
    val grouped = if (dark) Color.Black else Color(0xFFF2F2F7)
    val cell = if (dark) Color(0xFF1C1C1E) else Color.White
    val label = if (dark) Color.White else Color.Black
    val secondary = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)
    val tertiary = if (dark) Color(0x4DEBEBF5) else Color(0x4D3C3C43)
    val separator = if (dark) Color(0xFF38383A) else Color(0xFFC6C6C8)
    val fill = if (dark) Color(0x3D767680) else Color(0x1F767680)
    val blue = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val red = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val green = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    /** The others' bubbles. */
    val theirs = if (dark) Color(0xFF26252A) else Color(0xFFE9E9EB)
    /** Tool bars (matt, the same as LiMail and LiCal). */
    val bar = if (dark) Color(0xFF121212) else Color(0xFFF9F9F9)
    val avatarTop = Color(0xFFA5ABB8)
    val avatarBottom = Color(0xFF858994)
}

val LocalPalette = staticCompositionLocalOf { Palette(false) }

@Composable
fun palette(): Palette = LocalPalette.current

val Inter = FontFamily(Font(R.font.inter))

fun style(size: Float, weight: Int = 400, color: Color = Color.Unspecified) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), color = color,
        letterSpacing = if (size >= 28) (-0.6).sp else 0.sp)
