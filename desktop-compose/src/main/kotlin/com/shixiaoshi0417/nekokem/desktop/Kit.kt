package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** The two looks the Android app offers, chosen in Settings. */
enum class Style(val wire: String, val title: String) { Miuix("miuix", "MIUIX"), Material("material", "Material 3 Expressive") }

enum class TextRole { Headline, Title, Body, BodyStrong, Label, Small, Tiny }

class KitColors(
    val window: Color, val sidebar: Color, val content: Color, val card: Color, val notice: Color,
    val text: Color, val summary: Color, val danger: Color, val divider: Color, val highlight: Color,
)

class NavEntry(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

// Passwords are drawn as dots; the field's state keeps the characters.
val PasswordMask = OutputTransformation { val count = length; if (count > 0) replace(0, count, "•".repeat(count)) }

/**
 * The widgets the pages are built from. Each style draws them with its own
 * library (MIUIX or Material 3 Expressive); the pages and AppState are shared.
 */
interface Kit {
    @Composable fun Theme(content: @Composable () -> Unit)
    @Composable fun colors(): KitColors
    /** The window root that hosts dialogs and popups. */
    @Composable fun Root(content: @Composable () -> Unit)
    @Composable fun Text(text: String, role: TextRole, modifier: Modifier = Modifier, color: Color? = null, mono: Boolean = false)
    @Composable fun Sidebar(header: @Composable () -> Unit, entries: List<NavEntry>, enabled: Boolean, footer: @Composable () -> Unit)
    @Composable fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit)
    @Composable fun Section(modifier: Modifier = Modifier, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit)
    @Composable fun SectionTitle(text: String, first: Boolean = false)
    @Composable fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit)
    @Composable fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun DangerButton(text: String, enabled: Boolean = true, onClick: () -> Unit)
    @Composable fun LinkButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun TextInput(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean, invalid: Boolean, modifier: Modifier = Modifier)
    @Composable fun SecretInput(state: TextFieldState, label: String, enabled: Boolean, invalid: Boolean, masked: Boolean, lines: Int, placeholder: String, modifier: Modifier = Modifier)
    @Composable fun Segments(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit)
    @Composable fun Check(checked: Boolean, enabled: Boolean, label: String, onClick: (() -> Unit)?)
    @Composable fun SwitchRow(title: String, summary: String, checked: Boolean, danger: Boolean = false, enabled: Boolean = true, onChange: (Boolean) -> Unit)
    @Composable fun ChoiceRow(title: String, summary: String, items: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit)
    @Composable fun ActionRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit)
    /** A row that only shows something: a title over its details. */
    @Composable fun InfoRow(title: String, summary: String, danger: Boolean = false)
    /** The page's two main actions side by side, the first one emphasized. */
    @Composable fun ButtonPair(first: String, second: String, enabled: Boolean, onFirst: () -> Unit, onSecond: () -> Unit)
    @Composable fun Progress(fraction: Float?, modifier: Modifier = Modifier)
    @Composable fun Dialog(show: Boolean, title: String, summary: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit)
}

val LocalKit = staticCompositionLocalOf<Kit> { MiuixKit }
