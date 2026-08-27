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
        versionCode = 126825
        versionName = "1.26.8.25"

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
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.kotlin.stdlib)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
