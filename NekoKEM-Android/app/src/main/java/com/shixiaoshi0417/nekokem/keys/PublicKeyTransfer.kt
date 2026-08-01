package com.shixiaoshi0417.nekokem.keys

/** Public metadata used to diagnose SAF transfer integrity. */
data class PublicKeyFileRecord(
    val length: Long,
    val sha256: String,
)

data class PublicKeyImportResult(
    val code: Int,
    val stagedInput: PublicKeyFileRecord?,
    val normalizedCandidate: PublicKeyFileRecord?,
    val coreParseResult: Int,
    val coreNormalizeResult: Int,
    val permissionResult: Int,
    val commitResult: Int,
    val commitErrno: Int?,
)

data class PublicKeyExportTrace(
    val code: Int,
    val defaultPublicKey: PublicKeyFileRecord?,
    val privateNormalizedOutput: PublicKeyFileRecord?,
    val safOutput: PublicKeyFileRecord?,
)

data class PublicKeyImportTrace(
    val code: Int,
    val safCandidate: PublicKeyFileRecord?,
    val normalizedCandidate: PublicKeyFileRecord?,
    val coreParseResult: Int,
    val coreNormalizeResult: Int,
    val permissionResult: Int,
    val commitResult: Int,
    val commitErrno: Int?,
)
