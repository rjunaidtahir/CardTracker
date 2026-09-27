pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "UAEFinancialTracker"
// The Android app needs the Android SDK. A Mac that only builds the iPhone app (Xcode) has none, so it's left out there.
val localSdk = file("local.properties").takeIf { it.exists() }?.readLines()
    ?.firstOrNull { it.startsWith("sdk.dir=") }?.removePrefix("sdk.dir=")
val androidSdk = listOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), localSdk)
    .firstOrNull { !it.isNullOrBlank() && file(it).exists() }
if (androidSdk != null) include(":app")
include(":shared")
