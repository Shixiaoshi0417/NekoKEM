# Applied only to the minified device-test build. The instrumentation calls
# app internals that the app itself may not use, so R8 must not remove or
# merge them; it may still rename them, and the test APK is linked against the
# same mapping. JNI and callback names therefore rely on proguard-rules.pro
# alone, exactly as in the release APK.
-keep,allowobfuscation class com.shixiaoshi0417.nekokem.** { *; }
