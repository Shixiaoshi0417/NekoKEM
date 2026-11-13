package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// Linux has no common default font, so the Latin text ships with the app
// (src/main/resources/fonts). Chinese, Japanese and Korean glyphs come from
// the system's fonts through the usual fallback.
private fun family(name: String, vararg weights: Pair<String, FontWeight>) =
    FontFamily(weights.map { (file, weight) -> Font(resource = "fonts/$name-$file.ttf", weight = weight) })

/** MiSans, the typeface of the system MIUIX comes from. */
val MiuixFont = family("MiSansLatin", "Regular" to FontWeight.Normal, "Medium" to FontWeight.Medium, "Demibold" to FontWeight.SemiBold, "Bold" to FontWeight.Bold)

/** Google Sans Flex, the Material 3 Expressive typeface. */
val MaterialFont = family("GoogleSansFlex", "Regular" to FontWeight.Normal, "Medium" to FontWeight.Medium, "SemiBold" to FontWeight.SemiBold, "Bold" to FontWeight.Bold)

val Mono = family("JetBrainsMono", "Regular" to FontWeight.Normal, "Bold" to FontWeight.Bold)
