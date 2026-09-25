import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The message and statement engine, shared by the Android and iPhone apps: SMS parsing (bank rules + smart reader),
 * the statement PDF reader, reconciliation, the spending rules and the duplicate-check key. Plain Kotlin only (no
 * platform libraries), so the same code and the same tests run on both.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
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

android {
    namespace = "com.uaefinancial.tracker.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
