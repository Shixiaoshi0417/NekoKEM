plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningEnvironment = mapOf(
    "storeFile" to providers.environmentVariable("NEKOKEM_RELEASE_STORE_FILE").orNull,
    "storePassword" to providers.environmentVariable("NEKOKEM_RELEASE_STORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("NEKOKEM_RELEASE_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("NEKOKEM_RELEASE_KEY_PASSWORD").orNull,
)
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}
val missingReleaseSigningValues = releaseSigningEnvironment
    .filterValues { it.isNullOrBlank() }
    .keys
val releaseSigningConfigured = missingReleaseSigningValues.isEmpty()

if (releaseTaskRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing requires environment variables: " +
            "NEKOKEM_RELEASE_STORE_FILE, NEKOKEM_RELEASE_STORE_PASSWORD, " +
            "NEKOKEM_RELEASE_KEY_ALIAS, NEKOKEM_RELEASE_KEY_PASSWORD " +
            "(missing: ${missingReleaseSigningValues.joinToString()})",
    )
}

android {
    namespace = "com.shixiaoshi0417.nekokem"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.shixiaoshi0417.nekokem"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.1.1"
        testInstrumentationRunner =
            "com.shixiaoshi0417.nekokem.TemporaryKeyInstrumentation"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningEnvironment["storeFile"]))
                storePassword = requireNotNull(releaseSigningEnvironment["storePassword"])
                keyAlias = requireNotNull(releaseSigningEnvironment["keyAlias"])
                keyPassword = requireNotNull(releaseSigningEnvironment["keyPassword"])
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
