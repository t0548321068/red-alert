// צופר חירום 2 (עריכת צופר): העיצוב מחדש של צופר חירום, עובד במלואו - שירות ההתרעות, Firebase, ווידג'טים, אריחים.
// אותו קוד ואותו מניפסט כמו צופר חירום (app/ - מועתק מענף pending-redesign בזמן הבנייה), בחבילה נפרדת
// (com.tal.tzofareditor) - מותקנת לצד צופר חירום, ומתעדכנת לבד מהגרסה שלה
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// הגרסה של עריכת צופר: כל בנייה עולה באחד לבד (2.0.0, 2.0.1, 2.0.2...) - הבנייה ב-GitHub מחשבת אותה מהגרסה
// שפורסמה אחרונה ומעבירה ב-EDITOR_VERSION. כאן: נקודת ההתחלה (לשנות רק כדי לקפוץ, למשל ל-2.1.0)
val editorBase = "2.0.0"
val editorVersion = System.getenv("EDITOR_VERSION") ?: editorBase

// המניפסט: של צופר חירום, עם השם והאייקון של צופר חירום 2 + העדכון העצמי שלה (editor-components.xml)
val genManifest = layout.buildDirectory.file("generated/v2manifest/AndroidManifest.xml").get().asFile
run {
    var m = file("../app/src/main/AndroidManifest.xml").readText()
    fun swap(a: String, b: String) { check(m.contains(a)) { "המניפסט של צופר חירום השתנה: לא נמצא $a" }; m = m.replace(a, b) }
    swap("android:name=\".App\"", "android:name=\".editor.EditorApp\"")
    swap("android:label=\"@string/app_name\"", "android:label=\"צופר חירום 2\"")
    swap("android:icon=\"@mipmap/ic_launcher\"", "android:icon=\"@mipmap/ic_editor\"")
    swap("android:roundIcon=\"@mipmap/ic_launcher\"", "android:roundIcon=\"@mipmap/ic_editor\"")
    m = m.replace("com.tal.redalert.lock", "com.tal.tzofareditor.lock")   // משימה נפרדת ממסך הנעילה של צופר חירום
    swap("</application>", file("src/main/editor-components.xml").readText() + "\n    </application>")
    genManifest.parentFile.mkdirs(); genManifest.writeText(m)
}

// Firebase (התראות מיידיות מהשרת): אותו פרויקט כמו צופר חירום. במקום התוסף google-services - אותם ערכים ישירות
@Suppress("UNCHECKED_CAST")
val gs = groovy.json.JsonSlurper().parse(file("../app/google-services.json")) as Map<String, Any?>
val gsProject = gs["project_info"] as Map<String, Any?>
val gsClient = (gs["client"] as List<Map<String, Any?>>).first()

android {
    namespace = "com.tal.redalert"   // אותו קוד ואותם משאבים כמו האפליקציה
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tal.tzofareditor"
        minSdk = 26
        targetSdk = 35
        // 2.0.1 = 2010001 (תמיד גדול מהקודם)
        versionCode = editorVersion.split('.').map { it.toInt() }.let { it[0] * 1_000_000 + it[1] * 1000 + it[2] } + 10_000
        versionName = editorVersion   // בפרטי היישום בטלפון - הגרסה של עריכת צופר
        resValue("string", "app_version", editorVersion)   // בתוך המסכים: הגרסה של צופר חירום 2 (EditorApp מכניס אותה)
        resValue("string", "editor_version", editorVersion)
        resValue("string", "build_number", System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        resValue("bool", "is_beta", "false")   // בתוך המסכים: "v2.0.5" (בלי "בטא")
        resValue("string", "google_app_id", ((gsClient["client_info"] as Map<String, Any?>)["mobilesdk_app_id"]).toString())
        resValue("string", "google_api_key", ((gsClient["api_key"] as List<Map<String, Any?>>).first()["current_key"]).toString())
        resValue("string", "gcm_defaultSenderId", gsProject["project_number"].toString())
        resValue("string", "project_id", gsProject["project_id"].toString())
        gsProject["storage_bucket"]?.let { resValue("string", "google_storage_bucket", it.toString()) }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets["main"].apply {
        manifest.srcFile(genManifest)
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
