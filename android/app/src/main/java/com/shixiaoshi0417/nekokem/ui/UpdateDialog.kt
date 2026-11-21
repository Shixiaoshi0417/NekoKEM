package com.shixiaoshi0417.nekokem.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.update.NoteLine
import com.shixiaoshi0417.nekokem.update.ReleaseInfo
import com.shixiaoshi0417.nekokem.update.ReleaseNotes
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tells the user a newer release exists. Nothing is downloaded here: the
 * release page opens in the browser, where the APK and its checksums are.
 * The release notes start folded; opened, they scroll inside a box that
 * takes at most about a third of the screen.
 */
@Composable
internal fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onOpen: () -> Unit,
    onIgnore: () -> Unit,
    onLater: () -> Unit,
    notesInitiallyExpanded: Boolean = false,
) {
    val miuix = isMiuixStyle()
    val linkColor = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val notes = remember(release.notes, chinese) { ReleaseNotes.lines(release.notes, chinese) }
    var expanded by rememberSaveable(release.version) { mutableStateOf(notesInitiallyExpanded) }
    AppAlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_available_title, release.version)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.update_available_message, currentVersion))
                if (notes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { expanded = !expanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(if (expanded) R.string.update_notes_hide else R.string.update_notes_show),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Medium,
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = LocalContentColor.current,
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            ReleaseNotesBox(notes, miuix)
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.action_ignore_version),
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onIgnore)
                        .padding(vertical = 4.dp),
                    color = linkColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.action_open_release), onClick = onOpen)
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.action_later), onClick = onLater)
        },
    )
}

/** The mark before a list item; not words, so not translated. */
private const val BULLET = "\u2022"

@Composable
private fun ReleaseNotesBox(notes: List<NoteLine>, miuix: Boolean) {
    // A third of the screen, but never so little that a few lines do not fit.
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 3).coerceIn(160, 320).dp
    val container = if (miuix) MiuixTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val base = LocalTextStyle.current
    val body = base.copy(fontSize = if (base.fontSize.isSpecified) base.fontSize * 0.875f else base.fontSize, lineHeight = 1.45.em)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(container, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        notes.forEachIndexed { index, line ->
            when (line.kind) {
                NoteLine.Kind.HEADING -> Text(
                    text = line.text,
                    modifier = if (index > 0) Modifier.padding(top = 8.dp) else Modifier,
                    style = body,
                    fontWeight = FontWeight.Medium,
                    color = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
                )
                NoteLine.Kind.ITEM -> Row {
                    Text(BULLET, Modifier.width(12.dp), style = body)
                    Text(line.text, Modifier.weight(1f), style = body)
                }
                NoteLine.Kind.TEXT -> Text(line.text, style = body)
            }
        }
    }
}
