pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RedAlert"
include(":app")
include(":relay")   // ממסר צופר - אפליקציה נפרדת לטלפון שבבית
include(":admin")   // ניהול צופר - בדיקת יישובים, הקראה וצלילים
