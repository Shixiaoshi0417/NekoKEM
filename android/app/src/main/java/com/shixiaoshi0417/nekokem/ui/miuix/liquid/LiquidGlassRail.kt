package com.shixiaoshi0417.nekokem.ui.miuix.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val RailWidth = 72.dp

private val RailShape = RoundedCornerShape(36.dp)
private val RailItemShape = RoundedCornerShape(28.dp)

/**
 * The tablet form of the floating tab bar: the same glass, standing on the
 * left edge, with the tabs stacked. The selected tab sits in a tinted pill,
 * as on the bar.
 */
@Composable
internal fun LiquidGlassRail(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onItemClick: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Column(
        modifier = modifier
            .width(RailWidth)
            .liquidGlass(backdrop, RailShape, colors.surfaceContainer, dark)
            .selectableGroup()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val tint = if (selected) colors.primary else colors.onSurface
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RailItemShape)
                    .then(if (selected) Modifier.background(colors.primary.copy(alpha = 0.15f)) else Modifier)
                    .semantics(mergeDescendants = true) { this.selected = selected }
                    .clickable(role = Role.Tab) { if (!selected) onItemClick(index) },
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Decorative: the label below names the tab.
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Text(
                    text = item.label,
                    color = tint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
