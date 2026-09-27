import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The message and statement engine, shared by the Android and iPhone apps: SMS parsing (bank rules + smart reader),
 * the statement PDF reader, reconciliation, the spending rules and the duplicate-check key. Plain Kotlin only (no
 * platform libraries), so the same code and the same tests run on both.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library") apply false
}

// Android target only where the Android SDK is installed (Android CI, Android Studio). A Mac building just the iPhone
// app with Xcode doesn't need it.
val localSdk = rootProject.file("local.properties").takeIf { it.exists() }?.readLines()
    ?.firstOrNull { it.startsWith("sdk.dir=") }?.removePrefix("sdk.dir=")
val withAndroid = listOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), localSdk)
    .any { !it.isNullOrBlank() && file(it).exists() }
if (withAndroid) apply(plugin = "com.android.library")

kotlin {
    if (withAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

if (withAndroid) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "com.uaefinancial.tracker.shared"
        compileSdk = 35
        defaultConfig { minSdk = 26 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
