import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val ttsRoadKeystore = rootProject.file("debug.keystore")
val ttsRoadKeystoreSha256 = "0f545e04bab055b8fac2a5979be2445cbff54bba2e070fef65f12a187b4ec3d1"

android {
    namespace = "dk.perspektiva.ttsroad"
    compileSdk = 37

    signingConfigs {
        create("ttsroad") {
            // This ignored file also has a protected offline backup. Both debug and release builds
            // use it so every installable APK stays on one signing lineage.
            storeFile = ttsRoadKeystore
            storePassword = providers.environmentVariable("TTSROAD_KEYSTORE_PASSWORD")
                .getOrElse("android")
            keyAlias = providers.environmentVariable("TTSROAD_KEY_ALIAS")
                .getOrElse("androiddebugkey")
            keyPassword = providers.environmentVariable("TTSROAD_KEY_PASSWORD")
                .getOrElse("android")
        }
    }

    defaultConfig {
        applicationId = "dk.perspektiva.ttsroad"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "0.8.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ttsroad")
        }
        release {
            // Keep release builds unminified until an on-device startup smoke test is automated.
            // 0.7.0 was the first minified release and R8 renamed a model Moshi reflects on while
            // the Application is starting, causing an immediate release-only crash.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ttsroad")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Robolectric reads the merged manifest and resources for the tests that need a Context.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val verifyTtsRoadSigningKey = tasks.register("verifyTtsRoadSigningKey") {
    group = "verification"
    description = "Fails APK builds unless the pinned TTSRoad signing keystore is present."

    doLast {
        if (!ttsRoadKeystore.isFile) {
            throw GradleException(
                "Missing debug.keystore in the repository root. " +
                    "Restore it from the secure signing-key backup.",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = ttsRoadKeystore.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        if (actual != ttsRoadKeystoreSha256) {
            throw GradleException(
                "debug.keystore does not match the pinned TTSRoad release key " +
                    "(expected $ttsRoadKeystoreSha256, got $actual).",
            )
        }
    }
}

tasks.configureEach {
    if (name == "validateSigningDebug" || name == "validateSigningRelease") {
        dependsOn(verifyTtsRoadSigningKey)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.common)
    // Offline downloads: StandaloneDatabaseProvider backs both the media cache index and the
    // download index, which is what makes a download survive an app restart.
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
