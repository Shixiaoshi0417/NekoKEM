plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this version also selects the Kotlin compiler.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
