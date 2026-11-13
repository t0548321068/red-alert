// עריכת צופר: אותו קוד בדיוק כמו צופר חירום (app/ - מועתק מענף beta בזמן הבנייה), עם שכבת עריכה מעל.
// המסכים הם המסכים האמיתיים - אחד לאחד. רק בלי שירות ההתרעות, בלי Firebase ובלי ווידג'טים
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// הגרסה של עריכת צופר: עולה באחד בכל פרסום (1.1.4, 1.1.5...) - ידנית, כאן
val editorVersion = "1.1.23"
// הגרסה של צופר חירום שהעריכה בנויה ממנה (מוצגת בשורת החיוויים כמו באפליקציה)
val appVersion = Regex("versionName = \"([^\"]+)\"").find(file("../app/build.gradle.kts").readText())?.groupValues?.get(1) ?: "0"

android {
    namespace = "com.tal.redalert"   // אותו קוד ואותם משאבים כמו האפליקציה
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.tzofareditor"
        minSdk = 26
        targetSdk = 35
        // 1.0.1 = 10001 (תמיד גדול מהקודם)
        versionCode = editorVersion.split('.').map { it.toInt() }.let { it[0] * 1_000_000 + it[1] * 1000 + it[2] } + 10_000
        versionName = appVersion   // בשורת החיוויים - כמו באפליקציה
        resValue("string", "editor_version", editorVersion)
        resValue("string", "build_number", System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        resValue("bool", "is_beta", "true")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets["main"].apply {
        java.srcDirs("../app/src/main/java", "src/main/java")
        res.srcDirs("../app/src/main/res", "src/main/res")
        assets.srcDirs("../app/src/main/assets", "src/main/assets")   // + "מה חדש" של עריכת צופר
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
