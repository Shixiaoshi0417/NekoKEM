package com.shixiaoshi0417.nekokem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the window gives the App. Phones keep the bottom bar; from
 * the medium width class (600 dp, most tablets in portrait and unfolded
 * foldables) navigation moves to a side rail; from the expanded class
 * (840 dp, tablets in landscape) contacts show the list and the open
 * contact side by side.
 */
@Immutable
internal data class WindowLayout(val rail: Boolean, val twoPane: Boolean) {
    companion object {
        val Compact = WindowLayout(rail = false, twoPane = false)

        fun forWidth(width: Dp) = WindowLayout(rail = width >= RailMinWidth, twoPane = width >= TwoPaneMinWidth)
    }
}

internal val RailMinWidth = 600.dp
internal val TwoPaneMinWidth = 840.dp

/** Cards and buttons stop growing here, so they never stretch across a tablet. */
internal val ContentMaxWidth = 640.dp

/** The list pane's width when contacts show two panes. */
internal val ListPaneWidth = 360.dp

internal val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Compact }

/** Centers [content] in a column no wider than [ContentMaxWidth]. */
@Composable
internal fun ContentWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ContentMaxWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth().fillMaxHeight(), content = content)
    }
}
