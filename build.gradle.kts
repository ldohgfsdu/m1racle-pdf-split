import java.io.File

// AGP 9 ships built-in Kotlin support that shares the Kotlin compiler with the
// Compose compiler plugin, so both must resolve to the same KGP version.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21") }
}

plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

// Standalone PDF range-splitting tool. No Reader modules are on the compile
// classpath: EPUB, notes, bookmarks, Room and the reader chrome are all absent.
// Public version identity is explicit; never derived from Git.
val toolVersionCode = 2
val toolVersionName = "1.0.0-verify"

val releaseSigningEnvironmentNames = listOf(
    "M1RACLE_RELEASE_STORE_FILE",
    "M1RACLE_RELEASE_STORE_PASSWORD",
    "M1RACLE_RELEASE_KEY_ALIAS",
    "M1RACLE_RELEASE_KEY_PASSWORD",
)
val releaseSigningEnvironment = releaseSigningEnvironmentNames.associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
}
val providedReleaseSigningNames = releaseSigningEnvironment.filterValues { it != null }.keys
check(providedReleaseSigningNames.isEmpty() || providedReleaseSigningNames.size == releaseSigningEnvironmentNames.size) {
    val missing = releaseSigningEnvironmentNames.filterNot(providedReleaseSigningNames::contains)
    "Partial M1racleReader release signing environment; missing: ${missing.joinToString()}."
}
val hasInjectedReleaseSigning = providedReleaseSigningNames.size == releaseSigningEnvironmentNames.size
val injectedReleaseStoreFile = releaseSigningEnvironment.getValue("M1RACLE_RELEASE_STORE_FILE")
    ?.let(::File)
    ?.let { if (it.isAbsolute) it else rootProject.file(it.path) }

android {
    namespace = "com.m1racle.reader.pdfsplit"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.m1racle.reader.pdf"
        minSdk = 31
        targetSdk = 35
        versionCode = toolVersionCode
        versionName = toolVersionName
    }

    signingConfigs {
        getByName("debug")
        create("release") {
            if (hasInjectedReleaseSigning) {
                storeFile = injectedReleaseStoreFile
                storePassword = releaseSigningEnvironment.getValue("M1RACLE_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("M1RACLE_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("M1RACLE_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf(
                "/org/bouncycastle/pqc/crypto/picnic/lowmc.properties",
                "/org/bouncycastle/pqc/crypto/sike/p434.properties",
                "/org/bouncycastle/pqc/crypto/sike/p503.properties",
                "/org/bouncycastle/pqc/crypto/sike/p610.properties",
                "/org/bouncycastle/pqc/crypto/sike/p751.properties",
            )
        }
    }

    lint {
        // First cut of the standalone tool; release correctness is gated by the
        // build and the runtime read-back, not by lint style findings.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// ── Release gate: never fall back to debug or unsigned output ────────────────
val releaseSigning = android.signingConfigs.getByName("release")
val validatePublicReleaseConfiguration = tasks.register("validatePublicReleaseConfiguration") {
    group = "verification"
    description = "Rejects release output unless injected signing is complete."
    doLast {
        check(hasInjectedReleaseSigning) {
            "REFUSED public release: inject ${releaseSigningEnvironmentNames.joinToString()} via environment."
        }
        val store = releaseSigning.storeFile
        check(store != null && store.isFile) {
            "REFUSED public release: injected keystore does not exist or is not a file."
        }
        check(store.name != "debug.keystore") {
            "REFUSED public release: Android debug.keystore is not an acceptable signing identity."
        }
        check(releaseSigning.keyAlias != "androiddebugkey") {
            "REFUSED public release: androiddebugkey is not an acceptable signing alias."
        }
        logger.lifecycle("PDF_TOOL_RELEASE_IDENTITY versionCode=$toolVersionCode versionName=$toolVersionName")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validatePublicReleaseConfiguration)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Range/name parsing is pure Kotlin; it is covered on the JVM, not on device.
    testImplementation("junit:junit:4.13.2")
}
