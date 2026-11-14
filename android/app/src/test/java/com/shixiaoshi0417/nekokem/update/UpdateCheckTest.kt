package com.shixiaoshi0417.nekokem.update

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateCheckTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://example.invalid/"}"""

    @Test fun versionsCompareNumerically() {
        assertTrue(Releases.isNewer("v5.0.10", "5.0.9"))
        assertTrue(Releases.isNewer("6.0.0", "5.99.99"))
        assertFalse(Releases.isNewer("v5.0.0", "5.0.0"))
        assertFalse(Releases.isNewer("v4.4.0", "5.0.0"))
        assertFalse(Releases.isNewer("v5.1.0-rc1", "5.0.0"))
        assertFalse(Releases.isNewer("v5.1", "5.0.0"))
        assertFalse(Releases.isNewer("v5.1.0", ""))
    }

    @Test fun pageAddressIsBuiltFromTheVersion() {
        val parsed = Releases.parseLatest(release("v5.1.0"))
        assertEquals(ReleaseInfo("5.1.0", "https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v5.1.0"), parsed)
    }

    @Test fun malformedDraftAndOddTagsAreRefused() {
        assertNull(Releases.parseLatest("not json"))
        assertNull(Releases.parseLatest("{}"))
        assertNull(Releases.parseLatest(release("v5.1.0", draft = true)))
        assertNull(Releases.parseLatest(release("v5.1.0", prerelease = true)))
        assertNull(Releases.parseLatest(release("v5.1.0/../../evil")))
        assertNull(Releases.parseLatest(release("v5.1.0?x")))
    }

    @Test fun checkReportsEachOutcome() {
        val offline = ReleasePageSource { throw IOException("offline") }
        assertEquals(UpdateResult.Available(ReleaseInfo("5.1.0", Releases.PAGE_PREFIX + "v5.1.0")),
            Releases.check("5.0.0", { release("v5.1.0") }, offline))
        assertEquals(UpdateResult.UpToDate, Releases.check("5.0.0", { release("v5.0.0") }, offline))
        assertEquals(UpdateResult.Failed, Releases.check("5.0.0", { throw IOException("offline") }, offline))
        assertEquals(UpdateResult.Failed, Releases.check("5.0.0", { "<html>" }, offline))
    }

    @Test fun aFailedApiRequestFallsBackToTheReleasePage() {
        val limited = ReleaseSource { throw IOException("HTTP 403") }
        val page = ReleasePageSource { Releases.PAGE_PREFIX + "v5.1.0" }
        assertEquals(UpdateResult.Available(ReleaseInfo("5.1.0", Releases.PAGE_PREFIX + "v5.1.0")),
            Releases.check("5.0.0", limited, page))
        assertEquals(UpdateResult.UpToDate, Releases.check("5.1.0", limited, page))
        assertEquals(UpdateResult.UpToDate, Releases.check("5.1.0", { "<html>" }, page))
        // A working API answer is used as it is; the page is not asked.
        assertEquals(UpdateResult.UpToDate,
            Releases.check("5.0.0", { release("v5.0.0") }, { throw AssertionError("asked") }))
    }

    @Test fun onlyThisRepositorysPlainReleasePageIsARelease() {
        assertEquals(ReleaseInfo("5.1.0", Releases.PAGE_PREFIX + "v5.1.0"), Releases.parseLocation(Releases.PAGE_PREFIX + "v5.1.0"))
        for (bad in listOf(
            "", "https://github.com/Shixiaoshi0417/NekoKEM/releases", "https://github.com/login?return_to=x",
            "https://evil.example/Shixiaoshi0417/NekoKEM/releases/tag/v5.1.0",
            Releases.PAGE_PREFIX + "v5.1.0-rc1", Releases.PAGE_PREFIX + "v5.1.0/../x", Releases.PAGE_PREFIX + "v5.1.0?x",
            "http://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v5.1.0",
        )) assertNull(bad, Releases.parseLocation(bad))
    }

    @Test fun oversizedResponseIsRefused() {
        val ok = ByteArray(Releases.MAX_RESPONSE_BYTES) { 'a'.code.toByte() }
        assertEquals(Releases.MAX_RESPONSE_BYTES, Releases.readBounded(ByteArrayInputStream(ok)).length)
        val big = ByteArray(Releases.MAX_RESPONSE_BYTES + 1)
        try {
            Releases.readBounded(ByteArrayInputStream(big))
            throw AssertionError("an oversized response was read")
        } catch (_: IOException) {
        }
    }

    @Test fun automaticChecksAreOnAndDaily() {
        val defaults = UpdateSettingsStore.read(context)
        assertTrue(defaults.autoCheck)
        assertTrue(UpdateSettingsStore.due(defaults, System.currentTimeMillis()))
        val checked = defaults.copy(lastCheckMillis = 1_000_000L)
        assertFalse(UpdateSettingsStore.due(checked, 1_000_000L + UpdateSettingsStore.INTERVAL_MILLIS - 1))
        assertTrue(UpdateSettingsStore.due(checked, 1_000_000L + UpdateSettingsStore.INTERVAL_MILLIS))
        assertTrue(UpdateSettingsStore.due(checked, 10L))
        assertFalse(UpdateSettingsStore.due(checked.copy(autoCheck = false), 0L))
    }

    @Test fun choicesSurvive() {
        val chosen = UpdateSettings(autoCheck = false, lastCheckMillis = 42L, ignoredVersion = "5.1.0")
        assertTrue(UpdateSettingsStore.write(context, chosen))
        assertEquals(chosen, UpdateSettingsStore.read(context))
    }
}
