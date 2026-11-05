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
        versionCode = 126871 // Android upgrade requires monotonically increasing versionCode
        versionName = "v1.26.11.05.23.33" // v1.YY.MM.DD.hh.mm, Beijing time (UTC+8); update for each APK build

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
