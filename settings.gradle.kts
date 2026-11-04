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
include(":relay")   // צופר ממסר - אפליקציה נפרדת לטלפון שבבית
