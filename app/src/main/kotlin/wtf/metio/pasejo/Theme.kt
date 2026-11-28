// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The pasejo palette, tones of the mark's indigo, for phones without wallpaper colours. */
val PasejoLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF4A47A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2DFFF),
    onPrimaryContainer = Color(0xFF04006D),
    secondary = Color(0xFF5D5C72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF79536A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EC),
    onTertiaryContainer = Color(0xFF2E1125),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B21),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
    surfaceBright = Color(0xFFFCF8FF),
    surfaceDim = Color(0xFFDCD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2FA),
    surfaceContainer = Color(0xFFF0ECF4),
    surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE5E1E9),
)

val PasejoDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF1B1478),
    primaryContainer = Color(0xFF332E8F),
    onPrimaryContainer = Color(0xFFE2DFFF),
    secondary = Color(0xFFC6C4DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF45455A),
    onSecondaryContainer = Color(0xFFE2E0F9),
    tertiary = Color(0xFFE9B9D3),
    onTertiary = Color(0xFF46263A),
    tertiaryContainer = Color(0xFF5F3C51),
    onTertiaryContainer = Color(0xFFFFD8EC),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF47464F),
    surfaceBright = Color(0xFF39383F),
    surfaceDim = Color(0xFF131318),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1C1B21),
    surfaceContainer = Color(0xFF201F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
)

/**
 * Material 3 with the colours of the phone's wallpaper, which every
 * supported Android version offers, or the pasejo palette when
 * [wallpaperColours] is off.
 */
@Composable
fun PasejoTheme(
    dark: Boolean = isSystemInDarkTheme(),
    wallpaperColours: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colours = when {
        wallpaperColours && dark -> dynamicDarkColorScheme(context)
        wallpaperColours -> dynamicLightColorScheme(context)
        dark -> PasejoDark
        else -> PasejoLight
    }
    MaterialTheme(colorScheme = colours, content = content)
}
