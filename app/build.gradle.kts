plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tal.redalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.redalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"
    }

    // מפתח קבוע - כדי שכל גרסה חדשה תותקן מעל הקודמת
    signingConfigs {
        create("fixed") {
            storeFile = file("redalert.keystore")
            storePassword = "redalert123"
            keyAlias = "redalert"
            keyPassword = "redalert123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
