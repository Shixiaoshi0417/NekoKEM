package com.shixiaoshi0417.nekokem.keys

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException

/**
 * Hands the private-key password to the password manager the user picked in
 * system settings, and asks it for the password again later.
 *
 * NekoKEM never stores the password itself: it goes straight to the provider
 * through the system Credential Manager, which shows its own confirmation
 * sheet. Only Android 14 and later are supported, because older versions need
 * the Google Play services bridge, which NekoKEM does not ship.
 */
object PasswordManagerCredentials {
    enum class SaveResult { SAVED, CANCELLED, UNAVAILABLE, FAILED }

    sealed interface FillResult {
        class Filled(val password: ByteArray) : FillResult
        data object Cancelled : FillResult
        data object NotFound : FillResult
        data object Unavailable : FillResult
        data object Failed : FillResult
    }

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * The account name the password manager shows: the app name plus the
     * first 16 digits of the key fingerprint, so entries for different keys
     * stay apart. Without a public key there is no fingerprint to show.
     */
    fun credentialId(fingerprint: String?): String {
        val digits = fingerprint?.filter { it.isLetterOrDigit() }?.uppercase()
        if (digits.isNullOrEmpty()) return APP_ACCOUNT
        return "$APP_ACCOUNT " + digits.take(16).chunked(4).joinToString(" ")
    }

    /** Does not take ownership of [password]; the caller still clears it. */
    suspend fun save(activity: Activity, credentialId: String, password: ByteArray): SaveResult {
        if (!isSupported) return SaveResult.UNAVAILABLE
        return try {
            CredentialManager.create(activity).createCredential(
                activity,
                CreatePasswordRequest(credentialId, password.decodeToString()),
            )
            SaveResult.SAVED
        } catch (_: CreateCredentialCancellationException) {
            SaveResult.CANCELLED
        } catch (_: CreateCredentialNoCreateOptionException) {
            SaveResult.UNAVAILABLE
        } catch (_: CreateCredentialProviderConfigurationException) {
            SaveResult.UNAVAILABLE
        } catch (_: CreateCredentialUnsupportedException) {
            SaveResult.UNAVAILABLE
        } catch (_: CreateCredentialException) {
            SaveResult.FAILED
        }
    }

    /**
     * Asks for a saved password. [credentialId] limits the choice to entries
     * for one key, plus an entry saved before the key had a public key; null
     * offers every NekoKEM entry, for a key being imported.
     * The caller owns the returned bytes and must clear them.
     */
    suspend fun fill(activity: Activity, credentialId: String?): FillResult {
        if (!isSupported) return FillResult.Unavailable
        val option = GetPasswordOption(
            allowedUserIds = credentialId?.let { setOf(it, APP_ACCOUNT) } ?: emptySet(),
        )
        return try {
            val response = CredentialManager.create(activity).getCredential(
                activity,
                GetCredentialRequest(listOf(option)),
            )
            val credential = response.credential as? PasswordCredential
                ?: return FillResult.NotFound
            FillResult.Filled(credential.password.encodeToByteArray())
        } catch (_: GetCredentialCancellationException) {
            FillResult.Cancelled
        } catch (_: NoCredentialException) {
            FillResult.NotFound
        } catch (_: GetCredentialProviderConfigurationException) {
            FillResult.Unavailable
        } catch (_: GetCredentialUnsupportedException) {
            FillResult.Unavailable
        } catch (_: GetCredentialException) {
            FillResult.Failed
        }
    }

    private const val APP_ACCOUNT = "NekoKEM"
}
