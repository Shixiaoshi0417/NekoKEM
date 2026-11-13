import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Same Kotlin compiler as the Android app.
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

// Must match desktop/bridge/Cargo.toml; scripts/check_versions.py checks it.
version = "5.0.0"

// The bridge library (desktop/bridge) built for this platform, for tests and `run`.
val bridgeLibrary = providers.gradleProperty("nekokem.bridge").orElse(providers.environmentVariable("NEKOKEM_BRIDGE"))

kotlin {
    // Java's foreign function API is final from JDK 22; 25 is the current LTS.
    jvmToolchain(25)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("top.yukonga.miuix.kmp:miuix-ui-desktop:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-desktop:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-desktop:0.9.4")
    // The Material 3 Expressive style; its Expressive widgets ship only in the alphas.
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    // The filled navigation icons the Android app uses.
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    // The fingerprint QR code, the same encoder as the Android app.
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Dispatchers.Main on the Swing event thread, where Compose runs.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    bridgeLibrary.orNull?.let { systemProperty("nekokem.bridge", it) }
}

compose.desktop {
    application {
        mainClass = "com.shixiaoshi0417.nekokem.desktop.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")
        bridgeLibrary.orNull?.let { jvmArgs += "-Dnekokem.bridge=$it" }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "NekoKEM"
            packageVersion = version.toString()
            description = "Post-quantum file encryption"
            vendor = "Shixiaoshi0417"
            licenseFile.set(rootProject.file("../LICENSE"))
            // The bridge library is copied here per platform before packaging.
            appResourcesRootDir.set(layout.projectDirectory.dir("native"))
            modules("jdk.unsupported")
            windows {
                menu = true
                shortcut = true
                perUserInstall = false
                // Fixed so later versions upgrade the same installation.
                upgradeUuid = "6f3b2a54-6d1c-4e57-9b8e-2c7c0e1a4b6d"
                iconFile.set(rootProject.file("../windows/icons/icon.ico"))
            }
            linux {
                packageName = "nekokem"
                debMaintainer = "Shixiaoshi0417 <142963842+Shixiaoshi0417@users.noreply.github.com>"
                appCategory = "Utility"
                iconFile.set(rootProject.file("../windows/icons/128x128.png"))
            }
        }
    }
}

// Only Latin fonts ship with the app, so the Linux packages depend on Noto Sans CJK
// for Chinese, Japanese and Korean text (Fedora and openSUSE name the package differently)
// and on curl, which the update check runs.
// The value goes into jpackage's argument file as written, hence its own quotes.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    when (name) {
        "packageDeb" -> freeArgs.addAll("--linux-package-deps", "\"fonts-noto-cjk, curl\"")
        "packageRpm" -> freeArgs.addAll("--linux-package-deps", "\"(google-noto-sans-cjk-fonts or google-noto-sans-cjk-vf-fonts or noto-sans-cjk-fonts), curl\"")
    }
}

// Offscreen screenshots of both styles in light and dark, with the real bridge.
// The bridge keeps its files in a throwaway configuration directory.
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    description = "Renders the window into PNG files"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.shixiaoshi0417.nekokem.desktop.ScreenshotsKt"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    bridgeLibrary.orNull?.let { systemProperty("nekokem.bridge", it) }
    val config = layout.buildDirectory.dir("screenshot-config").get().asFile
    doFirst { config.deleteRecursively(); config.mkdirs() }
    environment("XDG_CONFIG_HOME", config.absolutePath)
    environment("LOCALAPPDATA", config.absolutePath)
    args(providers.gradleProperty("nekokem.shots").orElse(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath).get())
}
