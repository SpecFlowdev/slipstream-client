import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// A release build is signed with a real key when one is supplied, and with the
// debug key when one is not. The second case still produces a minified,
// installable APK — useful for testing what R8 did — but it is not a build to
// hand to anyone: the debug key is not secret, and an APK signed with it can
// never be upgraded to one signed properly.
val keystorePath: String? = System.getenv("SLIPSTREAM_KEYSTORE")
    ?.takeIf { it.isNotBlank() && File(it).exists() }

android {
    namespace = "dev.specflow.slipstream"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.specflow.slipstream"
        minSdk = 24
        targetSdk = 35
        // A release build takes its version from the tag being built, so the
        // number in the APK matches the one people downloaded it under.
        versionCode = (System.getenv("SLIPSTREAM_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SLIPSTREAM_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("SLIPSTREAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SLIPSTREAM_KEY_ALIAS") ?: "slipstream"
                keyPassword = System.getenv("SLIPSTREAM_KEY_PASSWORD")
                    ?: System.getenv("SLIPSTREAM_KEYSTORE_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // The tunnel and the packet bridge are programs, not libraries, and
            // a program has to exist as a file to be executed. Compressed
            // libraries are mapped straight out of the APK and have no path.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // A missing translation should not stop a build.
        disable += "MissingTranslation"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    // Encodes a Share QR bitmap; nothing here decodes one — scanning is left
    // to whatever camera app is already on the device (see the deep link in
    // the manifest), so there is no CameraX or Play Services dependency.
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
