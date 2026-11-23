package com.jurisgames.papeles.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

/** Light or dark: the system's by default, or the person's own pick. This device only. */
enum class DarkMode(val key: String, val label: String) { System("system", "Same as the system"), Light("light", "Light"), Dark("dark", "Dark") }

fun darkMode(dataDir: File): DarkMode {
    val k = runCatching { File(dataDir, "dark_mode").readText().trim() }.getOrNull()
    return DarkMode.entries.firstOrNull { it.key == k } ?: DarkMode.System
}

fun setDarkMode(dataDir: File, m: DarkMode) {
    runCatching { File(dataDir, "dark_mode").writeText(m.key) }
}

// The family's blue (Tasks, Sobres, Ciclo), paper-white surfaces; contrast checked at 4.5:1 for text on each.
private val light = lightColorScheme(
    primary = Color(0xFF2F5FC4), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FA), onPrimaryContainer = Color(0xFF0B2A66),
    secondaryContainer = Color(0xFFE6E9F2), onSecondaryContainer = Color(0xFF1B2333),
    background = Color(0xFFF6F7FA), onBackground = Color(0xFF15181F),
    surface = Color(0xFFF6F7FA), onSurface = Color(0xFF15181F),
    surfaceVariant = Color(0xFFE3E6EE), onSurfaceVariant = Color(0xFF444B5A),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEEF0F5),
    outline = Color(0xFF6F7687), outlineVariant = Color(0xFFC6CBD6),
)

private val dark = darkColorScheme(
    primary = Color(0xFFAFC4FF), onPrimary = Color(0xFF0B2A66),
    primaryContainer = Color(0xFF26437F), onPrimaryContainer = Color(0xFFDCE4FA),
    secondaryContainer = Color(0xFF2B3142), onSecondaryContainer = Color(0xFFDDE2EE),
    background = Color(0xFF111318), onBackground = Color(0xFFE3E5EB),
    surface = Color(0xFF111318), onSurface = Color(0xFFE3E5EB),
    surfaceVariant = Color(0xFF2A2E38), onSurfaceVariant = Color(0xFFC0C5D2),
    surfaceContainer = Color(0xFF1B1E25), surfaceContainerHigh = Color(0xFF232731),
    outline = Color(0xFF8D93A3), outlineVariant = Color(0xFF424857),
)

/** `dynamic` gives wallpaper colors where the platform has them (Android 12+). */
@Composable
fun AppTheme(mode: DarkMode, dynamic: ((Boolean) -> ColorScheme?)? = null, content: @Composable () -> Unit) {
    val isDark = when (mode) { DarkMode.System -> isSystemInDarkTheme(); DarkMode.Light -> false; DarkMode.Dark -> true }
    val cs = dynamic?.invoke(isDark) ?: if (isDark) dark else light
    val shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp))
    MaterialTheme(colorScheme = cs, shapes = shapes, content = content)
}
