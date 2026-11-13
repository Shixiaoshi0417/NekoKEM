package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** MIUIX, the Android app's default look. */
object MiuixKit : Kit {
    @Composable override fun Theme(content: @Composable () -> Unit) {
        // Follows the system's light or dark appearance, like the Android app.
        val controller = remember { ThemeController(ColorSchemeMode.System) }
        // The bundled MiSans replaces the system default in every MIUIX text style.
        val styles = remember {
            defaultTextStyles().run {
                fun TextStyle.own() = copy(fontFamily = MiuixFont)
                copy(main.own(), paragraph.own(), body1.own(), body2.own(), button.own(), footnote1.own(), footnote2.own(),
                    headline1.own(), headline2.own(), subtitle.own(), title1.own(), title2.own(), title3.own(), title4.own())
            }
        }
        MiuixTheme(controller = controller, textStyles = styles, content = content)
    }

    @Composable override fun colors() = MiuixTheme.colorScheme.let {
        KitColors(window = it.surface, sidebar = it.surface, content = it.surface, card = it.surfaceContainer, notice = it.secondaryContainer,
            text = it.onSurface, summary = it.onSurfaceVariantSummary, danger = it.error, divider = it.dividerLine, highlight = it.secondaryContainer)
    }

    @Composable override fun Root(content: @Composable () -> Unit) {
        Scaffold(containerColor = MiuixTheme.colorScheme.surface) { _ -> content() }
    }

    @Composable override fun Text(text: String, role: TextRole, modifier: Modifier, color: Color?, mono: Boolean) {
        val styles = MiuixTheme.textStyles
        val (style, weight) = when (role) {
            TextRole.Headline -> styles.title1 to FontWeight.SemiBold
            TextRole.Title -> styles.title4 to FontWeight.SemiBold
            TextRole.Body -> styles.body1 to null
            TextRole.BodyStrong -> styles.body1 to FontWeight.Medium
            TextRole.Label -> styles.body2 to FontWeight.Medium
            TextRole.Small -> styles.footnote1 to null
            TextRole.Tiny -> styles.footnote2 to null
        }
        val tint = color ?: if (role == TextRole.Small || role == TextRole.Tiny) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface
        MiuixText(text, modifier = modifier, color = tint, style = style, fontWeight = weight, fontFamily = if (mono) Mono else null)
    }

    @Composable override fun Sidebar(header: @Composable () -> Unit, entries: List<NavEntry>, enabled: Boolean, footer: @Composable () -> Unit) {
        NavigationRail(modifier = Modifier.fillMaxHeight(), expanded = true, showDivider = false, defaultWindowInsetsPadding = false, expandedWidth = 280.dp, header = { header() }) {
            // The selected page in MIUIX blue, as the Android app's navigation bar marks it.
            val blue = MiuixTheme.colorScheme.primary
            for (entry in entries) {
                val tint = if (entry.selected) blue else MiuixTheme.colorScheme.onSurface
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (entry.selected) blue.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(enabled = enabled, onClick = entry.onClick).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(entry.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    MiuixText(entry.label, color = tint, style = MiuixTheme.textStyles.body1, fontWeight = if (entry.selected) FontWeight.Medium else null, modifier = Modifier.padding(start = 16.dp))
                }
            }
            footer()
        }
    }

    @Composable override fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
            Icon(icon, contentDescription = label, tint = MiuixTheme.colorScheme.onSurface)
        }
    }

    @Composable override fun Section(modifier: Modifier, padded: Boolean, content: @Composable ColumnScope.() -> Unit) {
        Card(modifier.fillMaxWidth(), insideMargin = if (padded) PaddingValues(16.dp) else PaddingValues(0.dp), content = content)
    }

    @Composable override fun SectionTitle(text: String, first: Boolean) {
        SmallTitle(text, insideMargin = PaddingValues(start = 16.dp, top = if (first) 0.dp else 24.dp, bottom = 8.dp))
    }

    @Composable override fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
        TextButton(text = text, onClick = onClick, enabled = enabled, colors = ButtonDefaults.textButtonColorsPrimary())
    }

    @Composable override fun SecondaryButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        TextButton(text = text, onClick = onClick, enabled = enabled, modifier = modifier)
    }

    @Composable override fun DangerButton(text: String, enabled: Boolean, onClick: () -> Unit) {
        Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.error)) {
            MiuixText(text, color = MiuixTheme.colorScheme.onError, style = MiuixTheme.textStyles.button)
        }
    }

    @Composable override fun LinkButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        MiuixText(text, color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSecondaryVariant, style = MiuixTheme.textStyles.body2,
            modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp))
    }

    @Composable override fun TextInput(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean, invalid: Boolean, modifier: Modifier) {
        TextField(value = value, onValueChange = onValueChange, singleLine = true, enabled = enabled, modifier = modifier.semantics { contentDescription = label })
    }

    @Composable override fun SecretInput(state: TextFieldState, label: String, enabled: Boolean, invalid: Boolean, masked: Boolean, lines: Int, placeholder: String, modifier: Modifier) {
        TextField(
            state = state, label = placeholder, useLabelAsPlaceholder = placeholder.isNotEmpty(), enabled = enabled,
            outputTransformation = if (masked) PasswordMask else null,
            lineLimits = if (lines == 1) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(lines, lines),
            textStyle = if (lines == 1) MiuixTheme.textStyles.main else MiuixTheme.textStyles.main.copy(fontFamily = Mono, fontSize = 13.sp),
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }

    @Composable override fun Segments(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
        TabRowWithContour(tabs = labels, selectedTabIndex = selected, onTabSelected = { if (enabled) onSelect(it) }, modifier = Modifier.fillMaxWidth())
    }

    @Composable override fun Check(checked: Boolean, enabled: Boolean, label: String, onClick: (() -> Unit)?) {
        Checkbox(state = ToggleableState(checked), onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }

    @Composable override fun SwitchRow(title: String, summary: String, checked: Boolean, danger: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        val red = MiuixTheme.colorScheme.error
        SwitchPreference(
            checked = checked, onCheckedChange = onChange, title = title, summary = summary, enabled = enabled,
            titleColor = if (danger) BasicComponentDefaults.titleColor(color = red) else BasicComponentDefaults.titleColor(),
            summaryColor = if (danger) BasicComponentDefaults.summaryColor(color = red) else BasicComponentDefaults.summaryColor(),
        )
    }

    @Composable override fun ChoiceRow(title: String, summary: String, items: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
        OverlayDropdownPreference(items = items, selectedIndex = selected, title = title, summary = summary, enabled = enabled, onSelectedIndexChange = onSelect)
    }

    @Composable override fun ActionRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
        ArrowPreference(title = title, summary = summary.ifEmpty { null }, enabled = enabled, onClick = onClick)
    }

    @Composable override fun InfoRow(title: String, summary: String, danger: Boolean) {
        BasicComponent(title = title, summary = summary.ifEmpty { null },
            titleColor = if (danger) BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error) else BasicComponentDefaults.titleColor())
    }

    @Composable override fun ButtonPair(first: String, second: String, enabled: Boolean, onFirst: () -> Unit, onSecond: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = first, onClick = onFirst, enabled = enabled, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
            TextButton(text = second, onClick = onSecond, enabled = enabled, modifier = Modifier.weight(1f))
        }
    }

    @Composable override fun Progress(fraction: Float?, modifier: Modifier) {
        LinearProgressIndicator(progress = fraction, modifier = modifier.fillMaxWidth())
    }

    @Composable override fun Dialog(show: Boolean, title: String, summary: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }
}

