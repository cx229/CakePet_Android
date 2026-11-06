plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cx.cakepet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cx.cakepet"
        minSdk = 29
        targetSdk = 37
        versionCode = 126896 // Android upgrade requires monotonically increasing versionCode
        versionName = "v1.26.11.06.14.04" // v1.YY.MM.DD.hh.mm, Beijing time (UTC+8); update for each APK build

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Development-only key. Intentionally shareable because debug builds use a
    // distinct package ID; never use this key for the official release application.
    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/cakepet-shared-debug.jks")
            storePassword = "android"
            keyAlias = "cakepet-debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-gt"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        jvmToolchain(11)
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.kotlin.stdlib)
    implementation(libs.datastore.preferences)
    implementation(libs.recyclerview)
    implementation(libs.zip4j)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
