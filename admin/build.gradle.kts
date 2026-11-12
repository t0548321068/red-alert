// ניהול צופר: אפליקציה נפרדת לבדיקת היישובים, ההקראה והצלילים של צופר חירום
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// הצלילים של האפליקציה הראשית (res/raw) - מועתקים לכאן בזמן הבנייה, כדי שתמיד יהיו אותם קבצים
val copySounds = tasks.register<Copy>("copySounds") {
    from("../app/src/main/res/raw")
    into(layout.buildDirectory.dir("genassets/sounds"))
}

android {
    namespace = "com.tal.tzofaradmin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.tzofaradmin"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.6"
    }

    // הנתונים וההקלטות - ישירות מהתיקייה של האפליקציה הראשית (בלי עותק נפרד)
    sourceSets["main"].assets.srcDirs("../app/src/main/assets", layout.buildDirectory.dir("genassets"))

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild") { dependsOn(copySounds) }
