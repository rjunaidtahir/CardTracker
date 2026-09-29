import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.uaefinancial.tracker"
    compileSdk = 36

    defaultConfig {
        // Deliberately different from `namespace` above: this is the Play Store / installed-app
        // identity, changed once before first publish to drop the legacy "uaefinancial" name.
        // `namespace` (and every Kotlin file's own `package` line) stays as-is — it only controls
        // where the generated R/BuildConfig classes live, is invisible to users and app stores, and
        // renaming it would mean touching the entire source tree for zero user-facing benefit.
        applicationId = "com.filsspend.tracker"
        minSdk = 26
        targetSdk = 36
        // versionCode must strictly increase for Android to treat a new APK as an update to an
        // installed one — otherwise it silently refuses to install and the old app just stays put.
        // CI passes the run number (always increasing) via ANDROID_VERSION_CODE; local builds fall
        // back to a fixed placeholder, which is fine since those aren't distributed.
        versionCode = (System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 21)
        versionName = "2.1"
    }

    // Fixed debug key (committed) so every cloud build can install over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Play Store upload key. Only present when the RELEASE_* env vars are set (the Play release
        // workflow decodes the keystore from a GitHub secret before every build); local/CI builds
        // that don't set them still build a release APK/AAB, just unsigned.
        val releaseStorePath = System.getenv("RELEASE_STORE_FILE")
        if (releaseStorePath != null) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Category and chart icons (debug APK gets bigger; fine for a sideloaded app).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Reads the text of bank statement PDFs (incl. password-protected ones) for "Check against statement".
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // App lock (fingerprint / face). BiometricPrompt needs a FragmentActivity.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

// ---------------------------------------------------------------------------------------------------------------
// Strip BouncyCastle's EST package from the app.
//
// pdfbox-android (statement PDFs, incl. password-protected ones) depends on bcpkix. bcpkix also contains an EST
// client (org.bouncycastle.est: certificate enrolment over the network) with a trust-all X509TrustManager
// (JcaJceUtils). Nothing in this app or in PDFBox uses EST, and the app has no internet permission, but Google Play's
// security scan flags any trust-all TrustManager in the shipped code — including library code — and can block the
// app after a deadline. So the EST package is removed from the bcpkix jar before it is dexed. EST is a leaf package:
// nothing outside it refers to it, so PDF decryption and everything else is unaffected.
// ---------------------------------------------------------------------------------------------------------------
val bcEstStripped: Attribute<Boolean> = Attribute.of("fils.bcEstStripped", Boolean::class.javaObjectType)

abstract class StripBouncyCastleEst : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("bcpkix-")) {
            outputs.file(input)
            return
        }
        val output = outputs.file(input.nameWithoutExtension + "-no-est.jar")
        ZipInputStream(input.inputStream().buffered()).use { zin ->
            ZipOutputStream(output.outputStream().buffered()).use { zout ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name = entry.name
                    val isEst = name.startsWith("org/bouncycastle/est/")
                    // The jar's signature no longer matches once classes are removed; Android never checks it.
                    val isJarSignature = name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
                    if (isEst || isJarSignature) continue
                    zout.putNextEntry(ZipEntry(name))
                    zin.copyTo(zout)
                    zout.closeEntry()
                }
            }
        }
    }
}

dependencies {
    attributesSchema { attribute(bcEstStripped) }
    artifactTypes.maybeCreate("jar").attributes.attribute(bcEstStripped, false)
    registerTransform(StripBouncyCastleEst::class) {
        from.attribute(bcEstStripped, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(bcEstStripped, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    }
}

configurations.configureEach {
    if (isCanBeResolved && name.endsWith("untimeClasspath")) { // debugRuntimeClasspath, releaseRuntimeClasspath, …
        attributes.attribute(bcEstStripped, true)
    }
}
