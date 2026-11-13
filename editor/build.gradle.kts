// עריכת צופר: אפליקציה נפרדת - העתק של מסכי צופר חירום לעריכת הנראות, ושליחת כל השינויים בבת אחת
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tal.tzofareditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.tzofareditor"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.0"
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
