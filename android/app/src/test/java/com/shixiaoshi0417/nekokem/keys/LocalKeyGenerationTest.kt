package com.shixiaoshi0417.nekokem.keys

import android.content.Context
import android.content.ContextWrapper
import com.shixiaoshi0417.nekokem.files.HostPosixShadow
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [HostPosixShadow::class])
class LocalKeyGenerationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun checkGeneration(
        replace: Boolean,
        nativeResult: Int = NativeBridge.RESULT_SUCCESS,
        publicCheckFails: Boolean = false,
        privateCheck: () -> Boolean = { true },
        expectedResult: Int = LocalKeyManager.RESULT_STORAGE_ERROR,
    ) {
        val files = temporary.newFolder()
        val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = files
        }
        val keys = File(files, "keys").apply { mkdir() }
        val publicKey = File(keys, "public.key")
        val privateKey = File(keys, "private.nkpr.enc")
        if (replace) {
            publicKey.writeText("old public")
            privateKey.writeText("old private")
        }
        val password = "test password".toByteArray()
        val expectedReplace = replace
        var calls = 0
        val backend = object : KeyGeneration {
            override fun generate(
                publicPath: String,
                privatePath: String,
                password: ByteArray,
                replace: Boolean,
            ): Int {
                assertEquals(publicKey.absolutePath, publicPath)
                assertEquals(privateKey.absolutePath, privatePath)
                assertEquals(expectedReplace, replace)
                assertTrue(password.isNotEmpty())
                calls++
                if (nativeResult == NativeBridge.RESULT_SUCCESS) {
                    // Model Core's completed transaction: the old pair is gone.
                    publicKey.writeText("new public")
                    privateKey.writeText("new private")
                    if (publicCheckFails) {
                        Files.createLink(File(keys, "public-alias").toPath(), publicKey.toPath())
                    }
                }
                return nativeResult
            }

            override fun hasPrivateKey(privatePath: String): Boolean = privateCheck()
        }
        val manager = LocalKeyManager(context, backend)
        assertEquals(expectedResult, manager.generateKeypair(password, replace))
        assertEquals(1, calls)
        assertArrayEquals(ByteArray(password.size), password)
        val expectedPrefix = if (nativeResult == NativeBridge.RESULT_SUCCESS) "new" else "old"
        assertEquals("$expectedPrefix public", publicKey.readText())
        assertEquals("$expectedPrefix private", privateKey.readText())
    }

    @Test fun failedPrivateCheckPreservesReplacedPair() =
        checkGeneration(replace = true, privateCheck = { false })

    @Test fun failedPublicCheckPreservesReplacedPair() =
        checkGeneration(replace = true, publicCheckFails = true)

    @Test fun postCheckExceptionPreservesReplacedPair() =
        checkGeneration(replace = true, privateCheck = { throw IOException("temporary read failure") })

    @Test fun failedPrivateCheckAlsoPreservesNewPair() =
        checkGeneration(replace = false, privateCheck = { false })

    @Test fun successfulReplacementKeepsBothFiles() =
        checkGeneration(replace = true, expectedResult = NativeBridge.RESULT_SUCCESS)

    @Test fun nativeFailureLeavesOriginalPairAndSkipsPostChecks() =
        checkGeneration(
            replace = true,
            nativeResult = NativeBridge.RESULT_CORE_ERROR,
            privateCheck = { error("must not validate a failed native transaction") },
            expectedResult = NativeBridge.RESULT_CORE_ERROR,
        )
}
