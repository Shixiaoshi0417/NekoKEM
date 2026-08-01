package com.shixiaoshi0417.nekokem.ui

/**
 * Keeps transient UI disabling strictly scoped to the asynchronous task.
 * Cleanup and busy-state restoration run for success, failure and cancellation.
 */
internal suspend fun <T> runWithUiBusyReset(
    setBusy: (Boolean) -> Unit,
    cleanup: () -> Unit = {},
    operation: suspend () -> T,
): T {
    setBusy(true)
    return try {
        operation()
    } finally {
        try {
            cleanup()
        } finally {
            setBusy(false)
        }
    }
}
