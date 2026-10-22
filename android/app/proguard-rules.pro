# Native code binds to NativeBridge by its full JNI names
# (Java_com_shixiaoshi0417_nekokem_nativecore_NativeBridge_native*), so the
# class and its native methods keep their names.
-keepclasseswithmembernames,includedescriptorclasses class com.shixiaoshi0417.nekokem.nativecore.NativeBridge {
    native <methods>;
}

# progress_jni.c looks up onProgress(JJ)Z by name on the callback's runtime
# class, including the classes R8 synthesizes for Kotlin lambdas.
-keep interface com.shixiaoshi0417.nekokem.nativecore.NativeProgressCallback {
    boolean onProgress(long, long);
}
-keepclassmembers class * implements com.shixiaoshi0417.nekokem.nativecore.NativeProgressCallback {
    boolean onProgress(long, long);
}
