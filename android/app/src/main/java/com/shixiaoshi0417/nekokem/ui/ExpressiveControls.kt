package com.shixiaoshi0417.nekokem.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/** How strongly an action asks for attention; destructive actions use the error colors. */
internal enum class ActionEmphasis { FILLED, TONAL, OUTLINED, TEXT, DESTRUCTIVE }

/** Material 3 Expressive button sizes in use: medium marks a page's main actions. */
internal enum class ActionSize { SMALL, MEDIUM }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ActionSize.height: Dp
    get() = when (this) {
        ActionSize.SMALL -> ButtonDefaults.MinHeight
        ActionSize.MEDIUM -> ButtonDefaults.MediumContainerHeight
    }

/** One action of an [ActionButtonGroup]. */
internal data class GroupAction(
    val text: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/**
 * A Material 3 Expressive button: its corners morph while pressed, and its
 * text style and padding follow [size]. Labels wrap instead of being cut off
 * at large font scales.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: ActionEmphasis = ActionEmphasis.FILLED,
    size: ActionSize = ActionSize.SMALL,
    interactionSource: MutableInteractionSource? = null,
) {
    val height = size.height
    val shapes = ButtonDefaults.shapes()
    val padding = ButtonDefaults.contentPaddingFor(height)
    val sized = modifier.heightIn(min = height)
    val label: @Composable RowScope.() -> Unit = {
        Text(text, style = ButtonDefaults.textStyleFor(height), textAlign = TextAlign.Center)
    }
    when (emphasis) {
        ActionEmphasis.FILLED -> Button(
            onClick = onClick,
            shapes = shapes,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
            interactionSource = interactionSource,
            content = label,
        )
        ActionEmphasis.TONAL -> FilledTonalButton(
            onClick = onClick,
            shapes = shapes,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
            interactionSource = interactionSource,
            content = label,
        )
        ActionEmphasis.OUTLINED -> OutlinedButton(
            onClick = onClick,
            shapes = shapes,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
            interactionSource = interactionSource,
            content = label,
        )
        ActionEmphasis.TEXT -> TextButton(
            onClick = onClick,
            shapes = shapes,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
            interactionSource = interactionSource,
            content = label,
        )
        ActionEmphasis.DESTRUCTIVE -> FilledTonalButton(
            onClick = onClick,
            shapes = shapes,
            modifier = sized,
            enabled = enabled,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            contentPadding = padding,
            interactionSource = interactionSource,
            content = label,
        )
    }
}

/** A dialog action: a text button whose corners morph while pressed. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DialogButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, shapes = ButtonDefaults.shapes(), enabled = enabled) {
        Text(text)
    }
}

/**
 * Equal-width actions in a Material 3 Expressive button group: the pressed
 * button widens while its neighbors make room. Every item carries a weight,
 * so the group always fits its width and never moves an action into the
 * overflow menu; the menu entries only complete the group's contract. When
 * a label would not fit its share on one line, as with long translations or
 * large font scales, the actions stack at full width instead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActionButtonGroup(
    actions: List<GroupAction>,
    modifier: Modifier = Modifier,
    emphasis: ActionEmphasis = ActionEmphasis.FILLED,
    size: ActionSize = ActionSize.SMALL,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val style = ButtonDefaults.textStyleFor(size.height)
    val padding = ButtonDefaults.contentPaddingFor(size.height)
    val horizontalPadding = padding.calculateStartPadding(layoutDirection) +
        padding.calculateEndPadding(layoutDirection)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val spacing = ButtonGroupDefaults.HorizontalArrangement.spacing
        val share = (maxWidth - spacing * (actions.size - 1)) / actions.size
        val fits = actions.all { action ->
            val labelWidth = with(density) { measurer.measure(action.text, style).size.width.toDp() }
            labelWidth + horizontalPadding <= share
        }
        if (fits) {
            ButtonGroup(
                overflowIndicator = { menu -> ButtonGroupDefaults.OverflowIndicator(menu) },
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    customItem(
                        buttonGroupContent = {
                            val interactionSource = remember { MutableInteractionSource() }
                            ExpressiveButton(
                                text = action.text,
                                onClick = action.onClick,
                                modifier = Modifier.weight(1f).animateWidth(interactionSource),
                                enabled = action.enabled,
                                emphasis = emphasis,
                                size = size,
                                interactionSource = interactionSource,
                            )
                        },
                        menuContent = { menu ->
                            DropdownMenuItem(
                                text = { Text(action.text) },
                                enabled = action.enabled,
                                onClick = {
                                    menu.dismiss()
                                    action.onClick()
                                },
                            )
                        },
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                actions.forEach { action ->
                    ExpressiveButton(
                        text = action.text,
                        onClick = action.onClick,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = action.enabled,
                        emphasis = emphasis,
                        size = size,
                    )
                }
            }
        }
    }
}
