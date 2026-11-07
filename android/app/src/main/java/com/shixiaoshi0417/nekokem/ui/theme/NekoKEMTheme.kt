package com.shixiaoshi0417.nekokem.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.shixiaoshi0417.nekokem.ui.LocalUiStyle
import com.shixiaoshi0417.nekokem.ui.UiStyle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val DarkColors = darkColorScheme()

/**
 * Both interface styles run under the same theme tree, so switching style
 * keeps the content at one place in composition and none of its state is lost.
 * MIUIX always uses its own light or dark palette, as HyperOS apps do; only
 * the Material style follows wallpaper color. In the MIUIX style dialogs are
 * MIUIX dialogs, and the Material parts they still hold take a scheme built
 * from the MIUIX palette.
 */
@Composable
fun NekoKEMTheme(
    uiStyle: UiStyle = UiStyle.MATERIAL,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val controller = remember(darkTheme) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            isDark = darkTheme,
        )
    }
    val defaultOverscroll = LocalOverscrollFactory.current
    MiuixTheme(controller = controller) {
        // MIUIX press feedback and bounce scrolling; the Material theme below
        // would otherwise replace the former, and Material keeps its own glow.
        val miuixIndication = LocalIndication.current
        val miuixOverscroll = LocalOverscrollFactory.current
        val colorScheme = when {
            uiStyle == UiStyle.MIUIX -> MiuixTheme.colorScheme.toMaterial(darkTheme)
            dynamic -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> expressiveLightColorScheme()
        }
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            val miuix = uiStyle == UiStyle.MIUIX
            CompositionLocalProvider(
                LocalIndication provides if (miuix) miuixIndication else LocalIndication.current,
                LocalOverscrollFactory provides if (miuix) miuixOverscroll else defaultOverscroll,
                LocalUiStyle provides uiStyle,
                content = content,
            )
        }
    }
}

private fun Colors.toMaterial(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surfaceContainer,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = dividerLine,
    )
}
