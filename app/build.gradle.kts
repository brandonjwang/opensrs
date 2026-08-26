plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.opensrs"
    compileSdk = 35

    // Release versioning: the release workflow sets RELEASE_VERSION from the
    // pushed git tag (e.g. v0.3.1-alpha -> versionName "0.3.1-alpha"). Local
    // builds get a dev name; versionCode is the commit count, which only ever
    // grows on main (history was rewritten once, before any releases existed).
    val releaseTag: String? = System.getenv("RELEASE_VERSION")
    val commitCount: Int = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()

    defaultConfig {
        applicationId = "com.opensrs"
        minSdk = 29
        targetSdk = 35
        versionCode = commitCount
        versionName = releaseTag?.removePrefix("v") ?: "dev-$commitCount"

        // Room: export the schema of the *user-state* DB so future migrations can be
        // validated in CI. The pre-populated words DB has no migrations by design.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    signingConfigs {
        // Shared, committed debug keystore so local/dev builds (WSL, Windows
        // Studio) sign with the SAME SHA-1. Register that SHA-1 on the Cloud
        // console Android OAuth client; Drive sign-in then works from any dev
        // build. Debug keystores are non-sensitive by design.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release signing uses a PRIVATE keystore injected via CI secrets
        // (RELEASE_KEYSTORE_B64 -> release.keystore + the RELEASE_KEY_* envs).
        // Locally (env absent) it falls back to the debug key so assembleRelease
        // still builds on a developer machine.
        create("release") {
            val alias = System.getenv("RELEASE_KEY_ALIAS")
            if (alias != null) {
                keyAlias = alias
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""
                storeFile = rootProject.file("release.keystore")
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
            } else {
                storeFile = rootProject.file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Private key in CI; falls back to debug key locally.
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // WorkManager 2.6+ initializes via androidx.startup (the merged manifest
    // uses InitializationProvider with a WorkManagerInitializer meta-data entry,
    // never a standalone provider). The RemoveWorkManagerInitializer lint is a
    // known false positive for this setup, so disable it for release builds.
    lint {
        disable.add("RemoveWorkManagerInitializer")
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // The pre-populated spoken-frequency dictionary ships as a plain SQLite asset
    // copied into its own database on first run. It must survive AAPT resource
    // optimization and never be compressed into the APK incorrectly.
    androidResources {
        noCompress += "db"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Room (user state only; dictionary is a raw SQLite asset)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Preferences
    implementation(libs.androidx.datastore.preferences)

    // Navigation + background sync
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)

    // Google identity + Drive appDataFolder REST (OkHttp) — zero backend
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
