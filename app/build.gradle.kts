import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing is read from environment variables (GitHub Actions secrets)
// or an untracked keystore.properties file. Nothing secret is committed.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = System.getenv(env) ?: signingProps.getProperty(key)

// Offline neural voices: sherpa-onnx (Apache-2.0) is published as an AAR on GitHub Releases,
// not on Maven. It is downloaded once, checksum-verified, and kept out of git (app/libs is ignored).
val sherpaVersion = "2.0.1"
val sherpaSha256 = "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575"
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
fun sha256Of(f: File): String = java.security.MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
    md.digest().joinToString("") { "%02x".format(it) }
}
if (!sherpaAar.exists() || sha256Of(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${sherpaAar.name}"
    logger.lifecycle("Downloading $url")
    val tmp = File(sherpaAar.path + ".part")
    var conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
    var redirects = 0
    while (conn.responseCode in 300..399 && redirects++ < 5) {
        val next = conn.getHeaderField("Location"); conn.disconnect()
        conn = java.net.URI(next).toURL().openConnection() as java.net.HttpURLConnection
    }
    check(conn.responseCode == 200) { "sherpa-onnx download failed: HTTP ${conn.responseCode}" }
    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val actual = sha256Of(tmp)
    check(actual == sherpaSha256) { "sherpa-onnx checksum mismatch: $actual" }
    check(tmp.renameTo(sherpaAar) || (sherpaAar.delete() && tmp.renameTo(sherpaAar))) { "Could not move ${tmp.name} into place" }
}

android {
    namespace = "com.melhive.zenith"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.melhive.zenith"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("ZENITH_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("ZENITH_VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // LiteRT-LM ships native libraries for these ABIs only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "ZENITH_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "ZENITH_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ZENITH_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ZENITH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Minification stays off until the release build has been validated on a device:
            // an R8 problem would only show up as a crash at runtime.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Exported Room schemas are packaged into debug/test assets so MigrationTestHelper can
    // rebuild every historical version and verify migrations.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        // Neural voices ship for arm64 phones only; x86_64 (emulators) falls back to system voices.
        // This keeps ~27 MB of native code out of the APK.
        jniLibs.excludes += setOf("lib/x86_64/libsherpa-onnx-jni.so", "lib/x86/**", "lib/armeabi-v7a/**")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
    implementation(files(sherpaAar))
    implementation(libs.commons.compress)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
