plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jonathan.phonexboxcontroller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jonathan.phonexboxcontroller.v111"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
