import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.musiclify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musiclify"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4"

        // Shared song-match cache (services/matchcache). Forks can point this at their own
        // deployment with -PmatchCacheUrl=..., or disable it with -PmatchCacheUrl=
        val matchCacheUrl = (project.findProperty("matchCacheUrl") as String?)
            ?: "https://br-weathered-scene-bibw5h1p-matchcache.compute.c-5.ap-southeast-1.aws.neon.tech"
        buildConfigField("String", "MATCH_CACHE_URL", "\"$matchCacheUrl\"")
        manifestPlaceholders["appLabel"] = "Musiclify"
    }

    // Release signing comes from apps/android/keystore.properties (local, gitignored) or, in CI,
    // from MUSICLIFY_KEYSTORE_* environment variables. Without either, release builds are unsigned.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(key: String, env: String): String? =
        keystoreProps.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val releaseStoreFile = signingValue("storeFile", "MUSICLIFY_KEYSTORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "MUSICLIFY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MUSICLIFY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MUSICLIFY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // QA: `-PqaBuild` installs a release build next to the real one (own id and name).
            if (project.hasProperty("qaBuild")) {
                applicationIdSuffix = ".qa"
                manifestPlaceholders["appLabel"] = "Musiclify QA"
            }
        }
        debug {
            // Lets a development build sit next to the installed release build.
            // `-PlegacyDebugId` keeps the plain id instead, to update an install from before 0.5
            // (those builds were debug-signed as com.musiclify) without losing its library.
            if (!project.hasProperty("legacyDebugId")) {
                applicationIdSuffix = ".debug"
                manifestPlaceholders["appLabel"] = "Musiclify Dev"
            }
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by NewPipeExtractor
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // UI
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.palette:palette-ktx:1.0.0") // ambient light colours from cover art

    // Playback
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-database:1.8.0")

    // YouTube extraction (on device)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Local database
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    // QR scanning for device linking. ZXing is open source (Apache-2.0), so the app has no
    // Google Play Services dependency and can ship on F-Droid and de-Googled phones.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Unit tests (JVM). Android's own org.json is a stub off-device, so tests use the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
