package com.shixiaoshi0417.nekokem.keys

import com.shixiaoshi0417.nekokem.nativecore.NativeBridge

/** The native commit boundary, separate from Android's fallible post-checks. */
internal interface KeyGeneration {
    fun generate(publicPath: String, privatePath: String, password: ByteArray, replace: Boolean): Int
    fun hasPrivateKey(privatePath: String): Boolean
}

internal object NativeKeyGeneration : KeyGeneration {
    override fun generate(
        publicPath: String,
        privatePath: String,
        password: ByteArray,
        replace: Boolean,
    ): Int = if (replace) {
        NativeBridge.nativeReplaceKeypairWithPassword(publicPath, privatePath, password)
    } else {
        NativeBridge.nativeGenerateKeypairWithPassword(publicPath, privatePath, password)
    }

    override fun hasPrivateKey(privatePath: String): Boolean =
        NativeBridge.nativeHasPrivateKey(privatePath)
}
