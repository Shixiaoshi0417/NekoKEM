@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Text as MaterialText

private val Compact = PaddingValues(horizontal = 16.dp)

// The bundled Google Sans Flex in every Material text style.
private val MaterialTypography = Typography().run {
    fun TextStyle.own() = copy(fontFamily = MaterialFont)
    copy(displayLarge.own(), displayMedium.own(), displaySmall.own(), headlineLarge.own(), headlineMedium.own(), headlineSmall.own(),
        titleLarge.own(), titleMedium.own(), titleSmall.own(), bodyLarge.own(), bodyMedium.own(), bodySmall.own(),
        labelLarge.own(), labelMedium.own(), labelSmall.own())
}

/** Material 3 Expressive, the Android app's other look, laid out after FlClash's desktop app. */
object MaterialKit : Kit {
    @Composable override fun Theme(content: @Composable () -> Unit) {
        MaterialExpressiveTheme(
            colorScheme = if (isSystemInDarkTheme()) BlueDark else BlueLight,
            motionScheme = MotionScheme.expressive(),
            typography = MaterialTypography,
            content = content,
        )
    }

    // Surface levels as FlClash's prism_ui sets them: a grey page with white
    // cards in light mode, and cards one step above the page in dark mode.
    @Composable private fun dark() = isSystemInDarkTheme()
    @Composable override fun colors() = MaterialTheme.colorScheme.let {
        val dark = dark()
        KitColors(
            window = if (dark) it.surface else it.surfaceContainer,
            sidebar = if (dark) it.surfaceContainerLow else it.surfaceContainerHighest,
            content = if (dark) it.surface else it.surfaceContainer,
            card = if (dark) it.surfaceContainer else it.surfaceContainerLowest,
            notice = if (dark) it.surfaceContainerHigh else it.surfaceContainer,
            text = it.onSurface, summary = it.onSurfaceVariant, danger = it.error, divider = it.outlineVariant, highlight = it.primaryContainer,
        )
    }

    @Composable override fun Root(content: @Composable () -> Unit) {
        Surface(color = colors().window, contentColor = MaterialTheme.colorScheme.onSurface) { content() }
    }

    @Composable override fun Text(text: String, role: TextRole, modifier: Modifier, color: Color?, mono: Boolean) {
        // prism_ui's desktop scale: 24 page title, 15 text, 13 footnotes.
        val base = MaterialTheme.typography.bodyMedium
        val (size, weight) = when (role) {
            TextRole.Headline -> 24 to FontWeight.SemiBold
            TextRole.Title -> 15 to FontWeight.SemiBold
            TextRole.Body -> 15 to null
            TextRole.BodyStrong -> 15 to FontWeight.SemiBold
            TextRole.Label -> 13 to FontWeight.SemiBold
            TextRole.Small -> 13 to null
            TextRole.Tiny -> 12 to null
        }
        val style = base.copy(fontSize = size.sp, lineHeight = (size + 5).sp)
        val tint = color ?: if (role == TextRole.Small || role == TextRole.Tiny) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        MaterialText(text, modifier = modifier, color = tint, style = style, fontWeight = weight, fontFamily = if (mono) Mono else null)
    }

    @Composable override fun Sidebar(header: @Composable () -> Unit, entries: List<NavEntry>, enabled: Boolean, footer: @Composable () -> Unit) {
        // FlClash's sidebar, a little wider for these labels: 240 wide on the chrome surface with a hairline,
        // 40 tall rows, a faint fill and a short primary bar on the selected one.
        val scheme = MaterialTheme.colorScheme
        Row(Modifier.fillMaxHeight()) {
            Column(Modifier.width(240.dp).fillMaxHeight().background(colors().sidebar).padding(vertical = 4.dp)) {
                header()
                for (entry in entries) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (entry.selected) scheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable(enabled = enabled, onClick = entry.onClick).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(entry.icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            MaterialText(entry.label, color = scheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                        }
                        if (entry.selected) Box(Modifier.align(Alignment.CenterStart).offset(x = (-8).dp).size(3.dp, 16.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary))
                    }
                }
                footer()
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
        }
    }

    @Composable override fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) { Icon(icon, contentDescription = label) }
    }

    @Composable override fun Section(modifier: Modifier, padded: Boolean, content: @Composable ColumnScope.() -> Unit) {
        Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = colors().card)) {
            Column(if (padded) Modifier.padding(16.dp) else Modifier.padding(vertical = 4.dp), content = content)
        }
    }

    @Composable override fun SectionTitle(text: String, first: Boolean) {
        MaterialText(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = if (first) 0.dp else 24.dp, bottom = 8.dp))
    }

    @Composable override fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
        Button(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), contentPadding = Compact, modifier = Modifier.height(36.dp)) { MaterialText(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Composable override fun SecondaryButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(36.dp), shapes = ButtonDefaults.shapes(), contentPadding = Compact,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface)) { MaterialText(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Composable override fun DangerButton(text: String, enabled: Boolean, onClick: () -> Unit) {
        Button(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(),
            contentPadding = Compact, modifier = Modifier.height(36.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { MaterialText(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Composable override fun LinkButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        TextButton(onClick = onClick, enabled = enabled, modifier = modifier.height(36.dp), contentPadding = Compact) { MaterialText(text, fontSize = 15.sp) }
    }

    @Composable override fun TextInput(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean, invalid: Boolean, modifier: Modifier) {
        val focus = remember { MutableInteractionSource() }
        val focused by focus.collectIsFocusedAsState()
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true, enabled = enabled, interactionSource = focus,
            textStyle = fieldText(15.sp, null, enabled), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = modifier.semantics { contentDescription = label },
            decorationBox = { inner -> FieldBox(focused, invalid, enabled, singleLine = true, placeholder = "", empty = value.isEmpty(), inner = inner) },
        )
    }

    @Composable override fun SecretInput(state: TextFieldState, label: String, enabled: Boolean, invalid: Boolean, masked: Boolean, lines: Int, placeholder: String, modifier: Modifier) {
        val focus = remember { MutableInteractionSource() }
        val focused by focus.collectIsFocusedAsState()
        val single = lines == 1
        BasicTextField(
            state = state, enabled = enabled, interactionSource = focus,
            outputTransformation = if (masked) PasswordMask else null,
            lineLimits = if (single) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(lines, lines),
            textStyle = fieldText(if (single) 15.sp else 13.sp, if (single) null else Mono, enabled),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
            decorator = { inner -> FieldBox(focused, invalid, enabled, single, placeholder, state.text.isEmpty(), inner) },
        )
    }

    @Composable private fun fieldText(size: TextUnit, family: FontFamily?, enabled: Boolean) =
        MaterialTheme.typography.bodyMedium.copy(fontSize = size, fontFamily = family,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))

    /** A compact filled field, 40 tall, as FlClash draws its inputs; the outline shows only on focus or error. */
    @Composable private fun FieldBox(focused: Boolean, invalid: Boolean, enabled: Boolean, singleLine: Boolean, placeholder: String, empty: Boolean, inner: @Composable () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(10.dp)
        val edge = when { invalid -> scheme.error; focused -> scheme.primary; else -> Color.Transparent }
        Box(
            Modifier.fillMaxWidth().then(if (singleLine) Modifier.height(40.dp) else Modifier)
                .clip(shape).background(scheme.surfaceContainerHighest.copy(alpha = if (enabled) 1f else 0.6f))
                .border(if (focused || invalid) 2.dp else 0.dp, edge, shape)
                .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 10.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (empty && placeholder.isNotEmpty()) MaterialText(placeholder, fontSize = 15.sp, color = scheme.onSurfaceVariant)
            inner()
        }
    }

    @Composable override fun Segments(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
        // FlClash's segmented control: pills on a grey track, the chosen one filled blue.
        val scheme = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(if (dark()) scheme.surfaceContainerHigh else scheme.surfaceContainer).padding(4.dp)) {
            labels.forEachIndexed { index, label ->
                val chosen = index == selected
                Box(
                    Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(50)).background(if (chosen) scheme.primary else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) { MaterialText(label, color = if (chosen) scheme.onPrimary else scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }

    @Composable override fun Check(checked: Boolean, enabled: Boolean, label: String, onClick: (() -> Unit)?) {
        Checkbox(checked = checked, onCheckedChange = onClick?.let { { _: Boolean -> it() } }, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }

    @Composable override fun SwitchRow(title: String, summary: String, checked: Boolean, danger: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        val tint = if (danger) MaterialTheme.colorScheme.error else null
        ListItem(
            headlineContent = { MaterialText(title, color = tint ?: MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            supportingContent = { MaterialText(summary, color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable override fun ChoiceRow(title: String, summary: String, items: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
        var open by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { MaterialText(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            supportingContent = { MaterialText(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = {
                Box {
                    SecondaryButton(items.getOrElse(selected) { "" }, enabled) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        items.forEachIndexed { index, item -> DropdownMenuItem(text = { MaterialText(item) }, onClick = { open = false; onSelect(index) }) }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable override fun ActionRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
        ListItem(
            headlineContent = { MaterialText(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            supportingContent = { MaterialText(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = { SecondaryButton(title, enabled, onClick = onClick) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable override fun Progress(fraction: Float?, modifier: Modifier) {
        if (fraction == null) LinearWavyProgressIndicator(modifier = modifier.fillMaxWidth())
        else LinearWavyProgressIndicator(progress = { fraction }, modifier = modifier.fillMaxWidth())
    }

    @Composable override fun Dialog(show: Boolean, title: String, summary: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        if (!show) return
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { MaterialText(title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MaterialText(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    content()
                }
            },
            confirmButton = {},
        )
    }
}

// Material's blue baseline (seed #0061A4), light and dark: the Expressive
// style uses blue on the desktop, like the MIUIX style.
private val BlueLight = lightColorScheme(
    primary = Color(0xFF0061A4), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFD1E4FF), onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70), onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFD7E3F7), onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFF2DAFF), onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FF), onBackground = Color(0xFF191C20), surface = Color(0xFFF8F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB), onSurfaceVariant = Color(0xFF43474E), outline = Color(0xFF73777F), outlineVariant = Color(0xFFC3C7CF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F3FA), surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE), surfaceContainerHighest = Color(0xFFE1E2E8),
)
private val BlueDark = darkColorScheme(
    primary = Color(0xFF9ECAFF), onPrimary = Color(0xFF003258), primaryContainer = Color(0xFF00497D), onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB), onSecondary = Color(0xFF253140), secondaryContainer = Color(0xFF3B4858), onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4), onTertiary = Color(0xFF3B2948), tertiaryContainer = Color(0xFF523F5F), onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418), onBackground = Color(0xFFE1E2E8), surface = Color(0xFF111418), onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E), onSurfaceVariant = Color(0xFFC3C7CF), outline = Color(0xFF8D9199), outlineVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20), surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F), surfaceContainerHighest = Color(0xFF33353A),
)
