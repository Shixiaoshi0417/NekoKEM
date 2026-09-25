package com.shixiaoshi0417.nekokem

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.system.Os
import com.shixiaoshi0417.nekokem.files.SafFileWorkflow
import com.shixiaoshi0417.nekokem.keys.LocalKeyManager
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.nativecore.NativeProgressCallback
import com.shixiaoshi0417.nekokem.progress.CancellableProgressCallback
import com.shixiaoshi0417.nekokem.ui.runWithUiBusyReset
import com.shixiaoshi0417.nekokem.ui.FileInputState
import com.shixiaoshi0417.nekokem.ui.InputSelectionEvent
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Dependency-free, device-side JNI/Core integration tests. The runner uses an
 * isolated filesDir/cacheDir so it never replaces the debug App's normal keys.
 */
class TemporaryKeyInstrumentation : Instrumentation() {
    override fun onStart() {
        val results = Bundle()
        val status = try {
            val publicKeyTrace = runTemporaryKeyTests(targetContext)
            results.putString(RESULT_KEY, RESULT_SUCCESS)
            results.putString(PUBLIC_KEY_TRACE_KEY, publicKeyTrace)
            Activity.RESULT_OK
        } catch (error: Throwable) {
            results.putString(
                RESULT_KEY,
                error.message ?: error.javaClass.simpleName,
            )
            Activity.RESULT_CANCELED
        }
        finish(status, results)
    }

    private fun runTemporaryKeyTests(baseContext: Context): String {
        testBusyStateRestoration()
        testFileInputStateTransitions()
        val root = File(baseContext.cacheDir, TEST_ROOT_NAME)
        deleteTree(root)
        check(root.mkdir())
        Os.chmod(root.absolutePath, PRIVATE_DIRECTORY_MODE)

        var publicKeyTrace = ""
        try {
            val context = IsolatedStorageContext(baseContext, root)
            val manager = LocalKeyManager(context)
            val workflow = SafFileWorkflow(context, manager)
            val plaintext = File(context.cacheDir, PLAINTEXT_NAME)
            plaintext.writeBytes(TEST_PLAINTEXT)
            Os.chmod(plaintext.absolutePath, PRIVATE_FILE_MODE)

            publicKeyTrace = testPublicKeyRoundTrip(
                manager,
                workflow,
                context.cacheDir,
                plaintext,
            )
            testTemporaryKeys(manager, workflow, context.cacheDir, plaintext)
            testInvalidKeys(manager, workflow, context.cacheDir)
            testCancellationCleanup(workflow, context.cacheDir, plaintext)
            testKeyDeletion(manager)
            check(workflow.clearTemporaryKeyCache())
            check(!workflow.hasTemporaryKeyCache())
        } finally {
            deleteTree(root)
        }
        return publicKeyTrace
    }

    private fun testBusyStateRestoration() = runBlocking {
        val transitions = mutableListOf<Boolean>()
        var cleanupCount = 0
        val result = runWithUiBusyReset(
            setBusy = { transitions += it },
            cleanup = { cleanupCount += 1 },
        ) {
            check(transitions.last())
            BUSY_TEST_RESULT
        }
        check(result == BUSY_TEST_RESULT)
        check(transitions == listOf(true, false))
        check(cleanupCount == 1)

        transitions.clear()
        try {
            runWithUiBusyReset(
                setBusy = { transitions += it },
                cleanup = { cleanupCount += 1 },
            ) {
                throw IllegalStateException(EXPECTED_FAILURE)
            }
            error(EXPECTED_FAILURE_NOT_THROWN)
        } catch (error: IllegalStateException) {
            check(error.message == EXPECTED_FAILURE)
        }
        check(transitions == listOf(true, false))
        check(cleanupCount == 2)

        transitions.clear()
        try {
            runWithUiBusyReset(
                setBusy = { transitions += it },
                cleanup = { cleanupCount += 1 },
            ) {
                throw CancellationException(EXPECTED_CANCELLATION)
            }
            error(EXPECTED_CANCELLATION_NOT_THROWN)
        } catch (error: CancellationException) {
            check(error.message == EXPECTED_CANCELLATION)
        }
        check(transitions == listOf(true, false))
        check(cleanupCount == 3)
    }

    private fun testFileInputStateTransitions() {
        val original = FileInputState().transition(
            event = InputSelectionEvent.SELECT_NEW,
            newUriString = ORIGINAL_INPUT_URI,
            newDisplayName = ORIGINAL_INPUT_NAME,
        )
        check(original.isSelected)

        val retainingEvents = listOf(
            InputSelectionEvent.OPERATION_FAILED,
            InputSelectionEvent.OPERATION_FAILED,
            InputSelectionEvent.OPERATION_CANCELLED,
            InputSelectionEvent.RESULT_DISMISSED,
            InputSelectionEvent.KEY_SELECTION_CHANGED,
            InputSelectionEvent.KEY_SELECTION_CHANGED,
        )
        retainingEvents.forEach { event ->
            check(original.transition(event) === original)
        }
        check(
            !original.transition(
                InputSelectionEvent.ENCRYPTION_SUCCEEDED,
            ).isSelected,
        )
        check(
            !original.transition(
                InputSelectionEvent.DECRYPTION_SUCCEEDED,
            ).isSelected,
        )

        val replacement = original.transition(
            event = InputSelectionEvent.SELECT_NEW,
            newUriString = REPLACEMENT_INPUT_URI,
            newDisplayName = REPLACEMENT_INPUT_NAME,
        )
        check(replacement.isSelected)
        check(replacement != original)
        check(replacement.uriString == REPLACEMENT_INPUT_URI)
        check(replacement.displayName == REPLACEMENT_INPUT_NAME)

        val explicitlyCleared = replacement.transition(
            InputSelectionEvent.EXPLICIT_CLEAR,
        )
        check(!explicitlyCleared.isSelected)
        val invalidated = original.transition(
            InputSelectionEvent.URI_INVALIDATED,
        )
        check(!invalidated.isSelected)
    }

    private fun testPublicKeyRoundTrip(
        manager: LocalKeyManager,
        workflow: SafFileWorkflow,
        cache: File,
        plaintext: File,
    ): String {
        check(manager.generateKeypair(password(DEFAULT_PASSWORD)) ==
            NativeBridge.RESULT_SUCCESS)
        val originalFingerprint = checkNotNull(manager.readState().fingerprint)
        val exportedPublicKey = File(cache, EXPORTED_PUBLIC_KEY_NAME)
        val exportTrace = workflow.exportPublicKeyDetailed(
            Uri.fromFile(exportedPublicKey),
        )
        check(exportTrace.code == NativeBridge.RESULT_SUCCESS)
        val defaultRecord = checkNotNull(exportTrace.defaultPublicKey)
        val normalizedExport = checkNotNull(
            exportTrace.privateNormalizedOutput,
        )
        val safExport = checkNotNull(exportTrace.safOutput)
        check(defaultRecord == normalizedExport)
        check(normalizedExport == safExport)

        // Re-import while the default key still exists. This is the exact
        // device regression: the old code pre-created Core's output path.
        val replaceTrace = workflow.importPublicKeyDetailed(
            Uri.fromFile(exportedPublicKey),
        )
        assertSuccessfulPublicKeyImport(replaceTrace)
        check(manager.readState().fingerprint == originalFingerprint)

        // Required full loop: remove the live public key, import the exported
        // copy, then prove both default and one-shot temporary-key encryption.
        check(manager.deleteDefaultPublicKeyForTest())
        check(manager.readState().fingerprint == null)
        val importTrace = workflow.importPublicKeyDetailed(
            Uri.fromFile(exportedPublicKey),
        )
        assertSuccessfulPublicKeyImport(importTrace)
        val safCandidate = checkNotNull(importTrace.safCandidate)
        val normalizedImport = checkNotNull(importTrace.normalizedCandidate)
        check(safCandidate == safExport)
        check(normalizedImport == safExport)
        check(manager.readState().fingerprint == originalFingerprint)

        val encrypted = File(cache, DEFAULT_CIPHERTEXT_NAME)
        val decrypted = File(cache, DEFAULT_DECRYPTED_NAME)
        check(manager.encryptFile(plaintext, encrypted, CONTINUE_PROGRESS) ==
            NativeBridge.RESULT_SUCCESS)
        check(manager.decryptFile(
            encrypted,
            decrypted,
            password(DEFAULT_PASSWORD),
            CONTINUE_PROGRESS,
        ) == NativeBridge.RESULT_SUCCESS)
        check(plaintext.readBytes().contentEquals(decrypted.readBytes()))

        val selectedResult = workflow.stageTemporaryPublicKey(
            Uri.fromFile(exportedPublicKey),
            EXPORTED_PUBLIC_KEY_NAME,
        )
        check(selectedResult.code == NativeBridge.RESULT_SUCCESS)
        val selected = checkNotNull(selectedResult.key)
        check(selected.fingerprint == originalFingerprint)
        val temporaryEncrypted = File(
            cache,
            ROUND_TRIP_TEMPORARY_CIPHERTEXT_NAME,
        )
        val temporaryDecrypted = File(
            cache,
            ROUND_TRIP_TEMPORARY_DECRYPTED_NAME,
        )
        check(manager.encryptFile(
            plaintext,
            temporaryEncrypted,
            selected,
            CONTINUE_PROGRESS,
        ) == NativeBridge.RESULT_SUCCESS)
        workflow.discardTemporaryPublicKey(selected)
        check(manager.decryptFile(
            temporaryEncrypted,
            temporaryDecrypted,
            password(DEFAULT_PASSWORD),
            CONTINUE_PROGRESS,
        ) == NativeBridge.RESULT_SUCCESS)
        check(plaintext.readBytes().contentEquals(temporaryDecrypted.readBytes()))

        return "default=${record(defaultRecord)};" +
            "safExport=${record(safExport)};" +
            "safImport=${record(safCandidate)};" +
            "normalized=${record(normalizedImport)};" +
            "parse=${importTrace.coreParseResult};" +
            "normalize=${importTrace.coreNormalizeResult};" +
            "permission=${importTrace.permissionResult};" +
            "commit=${importTrace.commitResult};" +
            "errno=${importTrace.commitErrno ?: 0}"
    }

    private fun assertSuccessfulPublicKeyImport(
        trace: com.shixiaoshi0417.nekokem.keys.PublicKeyImportTrace,
    ) {
        check(trace.code == NativeBridge.RESULT_SUCCESS)
        check(trace.coreParseResult == NativeBridge.RESULT_SUCCESS)
        check(trace.coreNormalizeResult == NativeBridge.RESULT_SUCCESS)
        check(trace.permissionResult == NativeBridge.RESULT_SUCCESS)
        check(trace.commitResult == NativeBridge.RESULT_SUCCESS)
        check(trace.commitErrno == null)
        checkNotNull(trace.safCandidate)
        checkNotNull(trace.normalizedCandidate)
    }

    private fun record(
        value: com.shixiaoshi0417.nekokem.keys.PublicKeyFileRecord,
    ): String = "${value.length}:${value.sha256}"

    private fun testTemporaryKeys(
        manager: LocalKeyManager,
        workflow: SafFileWorkflow,
        cache: File,
        plaintext: File,
    ) {
        val pairDirectory = File(cache, TEMPORARY_PAIR_DIRECTORY)
        check(pairDirectory.mkdir())
        Os.chmod(pairDirectory.absolutePath, PRIVATE_DIRECTORY_MODE)
        val publicKey = File(pairDirectory, TEMPORARY_PUBLIC_NAME)
        val privateKey = File(pairDirectory, TEMPORARY_PRIVATE_NAME)
        check(generateNativeKeypair(
            publicKey,
            privateKey,
            TEMPORARY_PASSWORD,
        ) == NativeBridge.RESULT_SUCCESS)

        val publicResult = workflow.stageTemporaryPublicKey(
            Uri.fromFile(publicKey),
            TEMPORARY_PUBLIC_NAME,
        )
        check(publicResult.code == NativeBridge.RESULT_SUCCESS)
        val temporaryPublic = checkNotNull(publicResult.key)
        val encrypted = File(cache, TEMPORARY_CIPHERTEXT_NAME)
        check(manager.encryptFile(
            plaintext,
            encrypted,
            temporaryPublic,
            CONTINUE_PROGRESS,
        ) == NativeBridge.RESULT_SUCCESS)
        workflow.discardTemporaryPublicKey(temporaryPublic)

        val pendingResult = workflow.stageTemporaryPrivateKey(
            Uri.fromFile(privateKey),
            TEMPORARY_PRIVATE_NAME,
        )
        check(pendingResult.code == NativeBridge.RESULT_SUCCESS)
        val privateResult = workflow.validateTemporaryPrivateKey(
            checkNotNull(pendingResult.key),
            password(TEMPORARY_PASSWORD),
        )
        check(privateResult.code == NativeBridge.RESULT_SUCCESS)
        val temporaryPrivate = checkNotNull(privateResult.key)
        val decrypted = File(cache, TEMPORARY_DECRYPTED_NAME)
        check(manager.decryptFile(
            encrypted,
            decrypted,
            temporaryPrivate,
            password(TEMPORARY_PASSWORD),
            CONTINUE_PROGRESS,
        ) == NativeBridge.RESULT_SUCCESS)
        check(plaintext.readBytes().contentEquals(decrypted.readBytes()))
        workflow.discardTemporaryPrivateKey(temporaryPrivate)

        val wrongPasswordPending = workflow.stageTemporaryPrivateKey(
            Uri.fromFile(privateKey),
            TEMPORARY_PRIVATE_NAME,
        )
        val wrongPassword = workflow.validateTemporaryPrivateKey(
            checkNotNull(wrongPasswordPending.key),
            password(WRONG_PASSWORD),
        )
        check(wrongPassword.code != NativeBridge.RESULT_SUCCESS)
        check(wrongPassword.key == null)

        val mismatchPending = workflow.stageTemporaryPrivateKey(
            Uri.fromFile(privateKey),
            TEMPORARY_PRIVATE_NAME,
        )
        val mismatchResult = workflow.validateTemporaryPrivateKey(
            checkNotNull(mismatchPending.key),
            password(TEMPORARY_PASSWORD),
        )
        val validPrivate = checkNotNull(mismatchResult.key)
        val mismatchedPrivate = com.shixiaoshi0417.nekokem.keys.TemporaryPrivateKey(
            validPrivate.file,
            validPrivate.displayName,
            MISMATCHED_FINGERPRINT,
        )
        val mismatchOutput = File(cache, MISMATCH_OUTPUT_NAME)
        check(manager.decryptFile(
            encrypted,
            mismatchOutput,
            mismatchedPrivate,
            password(TEMPORARY_PASSWORD),
            CONTINUE_PROGRESS,
        ) == LocalKeyManager.RESULT_FINGERPRINT_MISMATCH)
        check(!mismatchOutput.exists())
        workflow.discardTemporaryPrivateKey(validPrivate)
    }

    private fun testInvalidKeys(
        manager: LocalKeyManager,
        workflow: SafFileWorkflow,
        cache: File,
    ) {
        val invalid = File(cache, INVALID_KEY_NAME)
        invalid.writeBytes(INVALID_KEY_BYTES)
        Os.chmod(invalid.absolutePath, PRIVATE_FILE_MODE)
        val normalized = File(cache, INVALID_NORMALIZED_NAME)
        check(manager.normalizePublicKey(invalid, normalized) !=
            NativeBridge.RESULT_SUCCESS)
        check(!normalized.exists())

        val publicResult = workflow.stageTemporaryPublicKey(
            Uri.fromFile(invalid),
            INVALID_KEY_NAME,
        )
        check(publicResult.code != NativeBridge.RESULT_SUCCESS)
        check(publicResult.key == null)

        val pending = workflow.stageTemporaryPrivateKey(
            Uri.fromFile(invalid),
            INVALID_KEY_NAME,
        )
        check(pending.code == NativeBridge.RESULT_SUCCESS)
        val privateResult = workflow.validateTemporaryPrivateKey(
            checkNotNull(pending.key),
            password(TEMPORARY_PASSWORD),
        )
        check(privateResult.code != NativeBridge.RESULT_SUCCESS)
        check(privateResult.key == null)
    }

    private fun testCancellationCleanup(
        workflow: SafFileWorkflow,
        cache: File,
        plaintext: File,
    ) {
        val pairDirectory = File(cache, CANCEL_PAIR_DIRECTORY)
        check(pairDirectory.mkdir())
        Os.chmod(pairDirectory.absolutePath, PRIVATE_DIRECTORY_MODE)
        val publicKey = File(pairDirectory, TEMPORARY_PUBLIC_NAME)
        val privateKey = File(pairDirectory, TEMPORARY_PRIVATE_NAME)
        check(generateNativeKeypair(
            publicKey,
            privateKey,
            CANCEL_PASSWORD,
        ) == NativeBridge.RESULT_SUCCESS)
        val selected = checkNotNull(
            workflow.stageTemporaryPublicKey(
                Uri.fromFile(publicKey),
                TEMPORARY_PUBLIC_NAME,
            ).key,
        )
        val outcome = workflow.prepareEncryption(
            Uri.fromFile(plaintext),
            CANCELLED_PROGRESS,
            selected,
        )
        check(outcome.code == NativeBridge.RESULT_CANCELLED)
        check(outcome.prepared == null)
        check(workflow.clearTemporaryKeyCache())
    }

    private fun testKeyDeletion(manager: LocalKeyManager) {
        val initial = manager.readState()
        check(initial.privateKeyExists)
        check(initial.fingerprint != null)
        check(manager.deletePublicKey() == NativeBridge.RESULT_SUCCESS)
        val privateOnly = manager.readState()
        check(privateOnly.privateKeyExists)
        check(privateOnly.fingerprint == null)
        check(manager.deleteKeypair() == NativeBridge.RESULT_SUCCESS)
        val empty = manager.readState()
        check(!empty.privateKeyExists)
        check(empty.fingerprint == null)
        check(manager.deleteKeypair() == NativeBridge.RESULT_SUCCESS)
    }

    private fun password(value: String): ByteArray =
        value.toByteArray(StandardCharsets.UTF_8)

    private fun generateNativeKeypair(
        publicKey: File,
        privateKey: File,
        value: String,
    ): Int {
        val password = password(value)
        return try {
            NativeBridge.nativeGenerateKeypairWithPassword(
                publicKey.absolutePath,
                privateKey.absolutePath,
                password,
            )
        } finally {
            password.fill(0)
        }
    }

    private fun deleteTree(root: File) {
        if (!root.exists()) {
            return
        }
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteTree(child)
            } else {
                child.delete()
            }
        }
        root.delete()
    }

    private class IsolatedStorageContext(
        base: Context,
        root: File,
    ) : ContextWrapper(base) {
        private val isolatedFiles = File(root, FILES_DIRECTORY).apply {
            check(mkdir())
            Os.chmod(absolutePath, PRIVATE_DIRECTORY_MODE)
        }
        private val isolatedCache = File(root, CACHE_DIRECTORY).apply {
            check(mkdir())
            Os.chmod(absolutePath, PRIVATE_DIRECTORY_MODE)
        }

        override fun getFilesDir(): File = isolatedFiles

        override fun getCacheDir(): File = isolatedCache
    }

    private companion object {
        const val RESULT_KEY = "result"
        const val PUBLIC_KEY_TRACE_KEY = "public-key-trace"
        const val RESULT_SUCCESS = "temporary-key-tests-passed"
        const val BUSY_TEST_RESULT = 7
        const val EXPECTED_FAILURE = "expected-operation-failure"
        const val EXPECTED_FAILURE_NOT_THROWN = "failure-not-thrown"
        const val EXPECTED_CANCELLATION = "expected-operation-cancellation"
        const val EXPECTED_CANCELLATION_NOT_THROWN =
            "cancellation-not-thrown"
        const val ORIGINAL_INPUT_URI = "content://nekokem.test/input/photo.jpg"
        const val ORIGINAL_INPUT_NAME = "photo.jpg"
        const val REPLACEMENT_INPUT_URI =
            "content://nekokem.test/input/archive.nkem"
        const val REPLACEMENT_INPUT_NAME = "archive.nkem"
        const val TEST_ROOT_NAME = "nekokem-instrumentation"
        const val FILES_DIRECTORY = "files"
        const val CACHE_DIRECTORY = "cache"
        const val PLAINTEXT_NAME = "plaintext.bin"
        const val DEFAULT_CIPHERTEXT_NAME = "default.nkem"
        const val DEFAULT_DECRYPTED_NAME = "default.out"
        const val EXPORTED_PUBLIC_KEY_NAME = "exported-public.key"
        const val ROUND_TRIP_TEMPORARY_CIPHERTEXT_NAME =
            "round-trip-temporary.nkem"
        const val ROUND_TRIP_TEMPORARY_DECRYPTED_NAME =
            "round-trip-temporary.out"
        const val TEMPORARY_PAIR_DIRECTORY = "temporary-pair"
        const val CANCEL_PAIR_DIRECTORY = "cancel-pair"
        const val TEMPORARY_PUBLIC_NAME = "public.key"
        const val TEMPORARY_PRIVATE_NAME = "private.nkpr"
        const val TEMPORARY_CIPHERTEXT_NAME = "temporary.nkem"
        const val TEMPORARY_DECRYPTED_NAME = "temporary.out"
        const val INVALID_KEY_NAME = "invalid.keydata"
        const val INVALID_NORMALIZED_NAME = "invalid-normalized.keydata"
        const val MISMATCH_OUTPUT_NAME = "mismatch.out"
        const val DEFAULT_PASSWORD = "default-test-password"
        const val TEMPORARY_PASSWORD = "temporary-test-password"
        const val CANCEL_PASSWORD = "cancel-test-password"
        const val WRONG_PASSWORD = "incorrect-password"
        const val MISMATCHED_FINGERPRINT =
            "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:" +
                "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00"
        const val PRIVATE_DIRECTORY_MODE = 0x1C0
        const val PRIVATE_FILE_MODE = 0x180

        val TEST_PLAINTEXT = ByteArray(256 * 1024) { index ->
            (index and 0xFF).toByte()
        }
        val INVALID_KEY_BYTES = "not-a-nekokem-key".toByteArray(
            StandardCharsets.US_ASCII,
        )
        val CONTINUE_PROGRESS = NativeProgressCallback { _, _ -> true }
        val CANCELLED_PROGRESS = object : CancellableProgressCallback {
            override fun isCancelled(): Boolean = true

            override fun onProgress(
                processedBytes: Long,
                totalBytes: Long,
            ): Boolean = false
        }
    }
}
