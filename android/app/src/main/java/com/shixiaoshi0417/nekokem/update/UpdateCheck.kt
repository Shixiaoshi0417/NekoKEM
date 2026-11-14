package com.shixiaoshi0417.nekokem.update

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** A published NekoKEM release newer than this App. */
data class ReleaseInfo(val version: String, val pageUrl: String)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val release: ReleaseInfo) : UpdateResult
    data object Failed : UpdateResult
}

/** Reads the latest release document; replaced by a local stub in device tests. */
fun interface ReleaseSource {
    /** Returns the response body, or throws [IOException]. */
    fun latestRelease(): String
}

/** Reads where GitHub's "latest release" page redirects to. */
fun interface ReleasePageSource {
    /** Returns the redirect's Location, or throws [IOException]. */
    fun latestLocation(): String
}

object Releases {
    const val LATEST_URL = "https://api.github.com/repos/Shixiaoshi0417/NekoKEM/releases/latest"
    const val LATEST_PAGE_URL = "https://github.com/Shixiaoshi0417/NekoKEM/releases/latest"
    const val PAGE_PREFIX = "https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/"

    /** A release document is a few KiB; anything larger is refused unread. */
    const val MAX_RESPONSE_BYTES = 1 shl 20
    private const val TIMEOUT_MS = 10_000
    private val VERSION = Regex("""^v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")

    /** Only GitHub's fixed API address is ever contacted, over HTTPS. */
    val github = ReleaseSource {
        val connection = URL(LATEST_URL).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "NekoKEM-Android")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use(::readBounded)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The website's "latest release" address, which answers with a redirect to
     * the newest full release. It is not part of the API, so the API's hourly
     * limit on anonymous requests from one address does not apply to it.
     */
    val githubPage = ReleasePageSource {
        val connection = URL(LATEST_PAGE_URL).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NekoKEM-Android")
            if (connection.responseCode !in 301..308) throw IOException("HTTP ${connection.responseCode}")
            connection.getHeaderField("Location") ?: throw IOException("No redirect")
        } finally {
            connection.disconnect()
        }
    }

    @Volatile
    var source: ReleaseSource = github

    /** Asked only when [source] fails, for example once the API limit is used up. */
    @Volatile
    var pageSource: ReleasePageSource = githubPage

    fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Release document too large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** Parses "5.0.0" or "v5.0.0"; anything else, such as a pre-release, is null. */
    fun parseVersion(text: String): List<Int>? =
        VERSION.matchEntire(text.trim())?.groupValues?.drop(1)?.map(String::toInt)

    fun isNewer(candidate: String, current: String): Boolean {
        val remote = parseVersion(candidate) ?: return false
        val local = parseVersion(current) ?: return false
        for (index in remote.indices) {
            if (remote[index] != local[index]) return remote[index] > local[index]
        }
        return false
    }

    /**
     * The release in [body], or null when the document is malformed, a draft
     * or pre-release, or names a tag that is not a plain version. The page
     * address is built from the version, never taken from the response.
     */
    fun parseLatest(body: String): ReleaseInfo? = try {
        val release = JSONObject(body)
        val tag = release.getString("tag_name")
        val version = parseVersion(tag)
        if (release.optBoolean("draft") || release.optBoolean("prerelease") || version == null) {
            null
        } else {
            val name = version.joinToString(".")
            ReleaseInfo(version = name, pageUrl = PAGE_PREFIX + "v" + name)
        }
    } catch (_: JSONException) {
        null
    }

    /**
     * The release a redirect [location] names, or null unless it is exactly
     * this repository's release page for a plain version.
     */
    fun parseLocation(location: String): ReleaseInfo? {
        if (!location.startsWith(PAGE_PREFIX)) return null
        val name = parseVersion(location.removePrefix(PAGE_PREFIX))?.joinToString(".") ?: return null
        return ReleaseInfo(version = name, pageUrl = PAGE_PREFIX + "v" + name)
    }

    private fun <T> attempt(read: () -> T?): T? = try {
        read()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    /**
     * Asks [source] for the latest release and, if that fails, [pageSource];
     * blocks, so call it off the main thread.
     */
    fun check(
        currentVersion: String,
        source: ReleaseSource = this.source,
        pageSource: ReleasePageSource = this.pageSource,
    ): UpdateResult {
        val release = attempt { parseLatest(source.latestRelease()) }
            ?: attempt { parseLocation(pageSource.latestLocation()) }
            ?: return UpdateResult.Failed
        return if (isNewer(release.version, currentVersion)) UpdateResult.Available(release) else UpdateResult.UpToDate
    }
}

/** Update-check choices: automatic checks are on unless the user turns them off. */
data class UpdateSettings(
    val autoCheck: Boolean = true,
    val lastCheckMillis: Long = 0L,
    val ignoredVersion: String? = null,
)

object UpdateSettingsStore {
    private const val PREFERENCES = "update_settings"
    private const val AUTO = "auto_check"
    private const val LAST = "last_check"
    private const val IGNORED = "ignored_version"
    const val INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

    fun read(context: Context): UpdateSettings = try {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        UpdateSettings(
            autoCheck = preferences.getBoolean(AUTO, true),
            lastCheckMillis = preferences.getLong(LAST, 0L),
            ignoredVersion = preferences.getString(IGNORED, null),
        )
    } catch (_: ClassCastException) {
        UpdateSettings()
    }

    fun write(context: Context, settings: UpdateSettings): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO, settings.autoCheck)
            .putLong(LAST, settings.lastCheckMillis)
            .putString(IGNORED, settings.ignoredVersion)
            .commit()

    /** An automatic check is due once a day; a clock set back also allows one. */
    fun due(settings: UpdateSettings, now: Long): Boolean =
        settings.autoCheck && (now - settings.lastCheckMillis >= INTERVAL_MILLIS || now < settings.lastCheckMillis)
}
