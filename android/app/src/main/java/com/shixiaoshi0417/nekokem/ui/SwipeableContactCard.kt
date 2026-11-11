package com.shixiaoshi0417.nekokem.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.keys.PublicKeyContact
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val PinBlue = Color(0xFF3482FF)
private val ArchiveGray = Color(0xFF8E8E93)
private val ActionWidth = 84.dp
private val PinThreshold = 88.dp

/**
 * A contact card that slides sideways, as in mail apps. Swiping right past the
 * threshold pins or unpins it; swiping left uncovers Archive and Delete, which
 * stay open until one is tapped or the card is swiped back. Archived contacts
 * cannot be pinned, so their card only slides left, to Unarchive and Delete.
 * The same actions are offered to accessibility services as custom actions.
 */
@Composable
internal fun SwipeableContactCard(
    contact: PublicKeyContact,
    enabled: Boolean,
    shape: Shape,
    onPin: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember(contact.id) { Animatable(0f) }
    val reveal = with(density) { (ActionWidth * 2).toPx() }
    val pinThreshold = with(density) { PinThreshold.toPx() }
    val canPin = !contact.archived
    val pinLabel = stringResource(if (contact.pinned) R.string.action_unpin else R.string.action_pin)
    val archiveLabel = stringResource(if (contact.archived) R.string.action_unarchive else R.string.action_archive)
    val deleteLabel = stringResource(R.string.action_delete)
    fun close() = scope.launch { offset.animateTo(0f) }
    val dragState = rememberDraggableState { delta ->
        scope.launch {
            val max = if (canPin) pinThreshold * 1.5f else 0f
            offset.snapTo((offset.value + delta).coerceIn(-reveal * 1.15f, max))
        }
    }
    Box(
        modifier.semantics {
            if (enabled) {
                customActions = buildList {
                    if (canPin) add(CustomAccessibilityAction(pinLabel) { onPin(!contact.pinned); true })
                    add(CustomAccessibilityAction(archiveLabel) { onArchive(!contact.archived); true })
                    add(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                }
            }
        },
    ) {
        // What the card uncovers: pin on the left, archive and delete on the right.
        Row(Modifier.matchParentSize().clip(shape)) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (offset.value > 0f) PinBlue else Color.Transparent),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (offset.value > 0f) SwipeLabel(pinLabel, Modifier.padding(start = 24.dp))
            }
            if (offset.value < 0f) {
                Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.End) {
                    SwipeAction(archiveLabel, ArchiveGray) {
                        close()
                        onArchive(!contact.archived)
                    }
                    SwipeAction(deleteLabel, UnverifiedRed) {
                        close()
                        onDelete()
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStopped = { velocity ->
                        when {
                            canPin && offset.value >= pinThreshold -> {
                                onPin(!contact.pinned)
                                offset.animateTo(0f)
                            }
                            offset.value < -reveal / 2 || (velocity < -1500f && offset.value < 0f) ->
                                offset.animateTo(-reveal)
                            else -> offset.animateTo(0f)
                        }
                    },
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Color.White, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
}

@Composable
private fun SwipeAction(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.width(ActionWidth).fillMaxHeight().background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SwipeLabel(text, Modifier.padding(horizontal = 6.dp))
    }
}
