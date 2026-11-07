plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")   // התראות מיידיות מהשרת (Firebase)
}

android {
    namespace = "com.tal.redalert"
    // בבדיקות בלי טלפון: פונקציות של אנדרואיד מחזירות ברירת מחדל במקום להיכשל
    testOptions { unitTests.isReturnDefaultValues = true }
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.redalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 352
        versionName = "3.67b"
        // מספר בנייה (עולה בכל בנייה) + האם זו גרסת בטא (כל ענף שאינו main)
        val runNum = System.getenv("GITHUB_RUN_NUMBER") ?: "0"
        val branch = System.getenv("GITHUB_REF_NAME") ?: "main"
        resValue("string", "build_number", runNum)
        resValue("bool", "is_beta", (branch != "main").toString())
        // המפה המובנית (MapLibre) - רק לטלפונים (ARM), כדי לא להגדיל את האפליקציה בחינם
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
    implementation("org.maplibre.gl:android-sdk:11.8.0")   // המפה המובנית
    // בדיקות אוטומטיות (רצות בכל בנייה)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}

// הבדיקות האוטומטיות רצות לפני כל בנייה של גרסה - אם משהו נשבר, הגרסה לא נוצרת
afterEvaluate {
    tasks.named("assembleRelease") { dependsOn("testReleaseUnitTest") }
}
