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
        versionCode = 98
        versionName = "1.32.23b"
        // מספר בנייה (עולה בכל בנייה) + האם זו גרסת בטא (כל ענף שאינו main)
        val runNum = System.getenv("GITHUB_RUN_NUMBER") ?: "0"
        val branch = System.getenv("GITHUB_REF_NAME") ?: "main"
        resValue("string", "build_number", runNum)
        resValue("bool", "is_beta", (branch != "main").toString())
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
