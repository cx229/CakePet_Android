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
        versionCode = 126836   // [TO AI] 每次修改版本号都需要递增
        versionName = "1.26.9.5" // [TO AI] 正式版本的1.<年后二位>.<月，无前导零>.<日，无前导零>,如果是测试版本，则增加前缀“t";如果需要，可能后面还会增加小时的标志

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
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
