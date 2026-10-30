plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")   // התראות מיידיות מהשרת (Firebase)
}

android {
    namespace = "com.tal.redalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.redalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 291
        versionName = "3.6"
        // מספר בנייה (עולה בכל בנייה) + האם זו גרסת בטא (כל ענף שאינו main)
        val runNum = System.getenv("GITHUB_RUN_NUMBER") ?: "0"
        val branch = System.getenv("GITHUB_REF_NAME") ?: "main"
        resValue("string", "build_number", runNum)
        resValue("bool", "is_beta", (branch != "main").toString())
    }

    // החתימה נעשית בבנייה ב-GitHub (build.yml) - המפתחות מוצפנים, לא בקוד
    buildTypes {
        release {
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
