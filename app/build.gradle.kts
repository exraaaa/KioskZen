import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingPropertiesFile = rootProject.file("keystore/signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        FileInputStream(signingPropertiesFile).use { load(it) }
    }
}
val hasReleaseSigning = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
).all { key -> !signingProperties.getProperty(key).isNullOrBlank() }

if (!hasReleaseSigning) {
    println("Release signing is not configured. Fill keystore/signing.properties to create installable release APKs.")
}

android {
    namespace = "com.zenpanel.kiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zenpanel.kiosk"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    // Keep debug artifacts as the default names, but produce a release APK name
    // that includes versionName and updates automatically when versionName changes.
    applicationVariants.all {
        val variant = this
        outputs.all {
            if (variant.buildType.name == "release") {
                val output = this as ApkVariantOutputImpl
                val resolvedVersionName = variant.versionName ?: "0.0.0"
                output.outputFileName = "KioskZen-v$resolvedVersionName-release.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // GeckoView (Mozilla engine).
    implementation("org.mozilla.geckoview:geckoview:147.0.20260212191108")
}
