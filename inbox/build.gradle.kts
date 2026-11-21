// פניות צופר: כל הפניות ממסך "יצירת קשר" במקום אחד, ותשובה לפונה. אפליקציה נפרדת - רק בטלפון של טל
// (ההתראה על פנייה חדשה מגיעה רק אליה, בנושא inbox - לא לכל טלפוני הבטא)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase: אותו פרויקט כמו צופר חירום - אותם ערכים ישירות (בלי התוסף google-services)
@Suppress("UNCHECKED_CAST")
val gs = groovy.json.JsonSlurper().parse(file("../app/google-services.json")) as Map<String, Any?>
val gsProject = gs["project_info"] as Map<String, Any?>
val gsClient = (gs["client"] as List<Map<String, Any?>>).first()

android {
    namespace = "com.tal.tzofarinbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.tzofarinbox"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.0"
        resValue("string", "google_app_id", ((gsClient["client_info"] as Map<String, Any?>)["mobilesdk_app_id"]).toString())
        resValue("string", "google_api_key", ((gsClient["api_key"] as List<Map<String, Any?>>).first()["current_key"]).toString())
        resValue("string", "gcm_defaultSenderId", gsProject["project_number"].toString())
        resValue("string", "project_id", gsProject["project_id"].toString())
        gsProject["storage_bucket"]?.let { resValue("string", "google_storage_bucket", it.toString()) }
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
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
