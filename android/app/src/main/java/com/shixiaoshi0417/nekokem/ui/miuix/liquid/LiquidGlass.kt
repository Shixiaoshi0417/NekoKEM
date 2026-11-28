package com.shixiaoshi0417.nekokem.ui.miuix.liquid

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight

/**
 * The floating bar's glass for other controls: the page behind is blurred,
 * bent at the edges and tinted with a neutral [color]; a thin rim light
 * marks the edge. Without a [backdrop] (before Android 13) it is a solid
 * [color] instead.
 */
internal fun Modifier.liquidGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    color: Color,
    dark: Boolean,
): Modifier = if (backdrop == null) {
    background(color, shape)
} else {
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // Room for the 16 dp lens to sample beyond the edge.
            padding = maxOf(padding, 24.dp.toPx())
            vibrancy()
            blur(4.dp.toPx(), 4.dp.toPx())
            lens(refractionHeight = 16.dp.toPx(), refractionAmount = 16.dp.toPx())
        },
        highlight = { if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight },
        onDrawSurface = { drawRect(color.copy(alpha = 0.4f)) },
    )
}

/**
 * Glass for a bar that spans the screen, such as the collapsed title bar: a
 * frosted [color] over a strong blur, with no lens or rim, since its edges
 * meet the screen's.
 */
internal fun Modifier.frostedBar(backdrop: LayerBackdrop?, color: Color): Modifier = if (backdrop == null) {
    background(color)
} else {
    drawBackdrop(
        backdrop = backdrop,
        shape = { androidx.compose.ui.graphics.RectangleShape },
        effects = {
            vibrancy()
            blur(24.dp.toPx(), 24.dp.toPx())
        },
        // The blur fades out at the screen's edges; the solid color beneath
        // keeps them from showing lighter.
        onDrawBehind = { drawRect(color) },
        onDrawSurface = { drawRect(color.copy(alpha = 0.7f)) },
    )
}
