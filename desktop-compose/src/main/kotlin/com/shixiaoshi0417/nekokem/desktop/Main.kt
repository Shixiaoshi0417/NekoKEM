package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    var window: java.awt.Frame? = null
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope, AwtFileChooser { window }, ::exitApplication) }
    Window(
        // A running operation is told to stop; the window closes once Core has cleaned up.
        onCloseRequest = state::requestClose,
        title = "NekoKEM",
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
    ) {
        window = this.window
        this.window.minimumSize = java.awt.Dimension(760, 560)
        LaunchedEffect(Unit) { state.start() }
        NekoKEMApp(state)
    }
}
