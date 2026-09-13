plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.snapask.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.snapask.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.activity:activity:1.9.2")
}
