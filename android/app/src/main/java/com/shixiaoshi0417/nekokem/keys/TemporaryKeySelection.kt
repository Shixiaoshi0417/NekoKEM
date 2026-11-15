package com.shixiaoshi0417.nekokem.keys

import java.io.File

/** App-private, one-operation public-key selection created from a SAF URI. */
class TemporaryPublicKey internal constructor(
    internal val file: File,
    val displayName: String,
    val fingerprint: String,
)

/** App-private NKPR candidate awaiting a one-shot Core password check. */
class PendingTemporaryPrivateKey internal constructor(
    internal val file: File,
    val displayName: String,
    /** The file is NKPR v1/v2: say so before a password is typed into it. */
    val lacksCommitment: Boolean = false,
) {
    private var active = true

    @Synchronized
    internal fun consume(): File? {
        if (!active) {
            return null
        }
        active = false
        return file
    }
}

/** App-private, password-verified NKPR selection for one decryption. */
class TemporaryPrivateKey internal constructor(
    internal val file: File,
    val displayName: String,
    val fingerprint: String,
)

class TemporaryPublicKeyResult internal constructor(
    val code: Int,
    val key: TemporaryPublicKey?,
)

class PendingTemporaryPrivateKeyResult internal constructor(
    val code: Int,
    val key: PendingTemporaryPrivateKey?,
)

class TemporaryPrivateKeyResult internal constructor(
    val code: Int,
    val key: TemporaryPrivateKey?,
)
