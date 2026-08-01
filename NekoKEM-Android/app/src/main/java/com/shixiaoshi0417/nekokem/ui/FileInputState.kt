package com.shixiaoshi0417.nekokem.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

internal enum class InputSelectionEvent {
    SELECT_NEW,
    EXPLICIT_CLEAR,
    URI_INVALIDATED,
    ENCRYPTION_SUCCEEDED,
    DECRYPTION_SUCCEEDED,
    OPERATION_FAILED,
    OPERATION_CANCELLED,
    RESULT_DISMISSED,
    KEY_SELECTION_CHANGED,
}

/** Input selection is independent from transient task and output state. */
internal data class FileInputState(
    val uriString: String? = null,
    val displayName: String? = null,
) {
    val isSelected: Boolean
        get() = !uriString.isNullOrEmpty() && !displayName.isNullOrEmpty()

    fun transition(
        event: InputSelectionEvent,
        newUriString: String? = null,
        newDisplayName: String? = null,
    ): FileInputState = when (event) {
        InputSelectionEvent.SELECT_NEW -> {
            if (newUriString.isNullOrEmpty() || newDisplayName.isNullOrEmpty()) {
                this
            } else {
                FileInputState(newUriString, newDisplayName)
            }
        }
        InputSelectionEvent.EXPLICIT_CLEAR,
        InputSelectionEvent.URI_INVALIDATED,
        InputSelectionEvent.ENCRYPTION_SUCCEEDED,
        InputSelectionEvent.DECRYPTION_SUCCEEDED -> FileInputState()
        InputSelectionEvent.OPERATION_FAILED,
        InputSelectionEvent.OPERATION_CANCELLED,
        InputSelectionEvent.RESULT_DISMISSED,
        InputSelectionEvent.KEY_SELECTION_CHANGED -> this
    }

    companion object {
        val Saver: Saver<FileInputState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.uriString.orEmpty(),
                    state.displayName.orEmpty(),
                )
            },
            restore = { values ->
                val uri = values.getOrNull(0).orEmpty()
                val name = values.getOrNull(1).orEmpty()
                if (uri.isEmpty() || name.isEmpty()) {
                    FileInputState()
                } else {
                    FileInputState(uri, name)
                }
            },
        )
    }
}
