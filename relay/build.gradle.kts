// ממסר צופר: אפליקציה נפרדת לטלפון שנשאר בבית - בודקת את פיקוד העורף מהאינטרנט הביתי ומעבירה לשרת
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tal.tzofarrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.tzofarrelay"
        minSdk = 26
        targetSdk = 34
        // מספר הגרסה עולה בכל בנייה
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
