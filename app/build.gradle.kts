plugins {
    alias(libs.plugins.android.application)
}


// Single authoritative release note for each build; generated into build/ and
// packaged as an asset, never written back into the source tree.
val embeddedNotesDir = layout.buildDirectory.dir("generated/atomic-release-assets")
val embedCurrentReleaseNotes by tasks.registering(Copy::class) {
    from(rootProject.file("RELEASE_NOTES.md"))
    into(embeddedNotesDir)
    rename { "current-release.md" }
}

// Older version notes are recovered from git history at build time and archived
// in a second small asset. In source-only ZIPs, the generator keeps current notes.
val archivedNotesDir = layout.buildDirectory.dir("generated/atomic-history-assets")
val embedReleaseHistory by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("scripts/generate-release-history.py").absolutePath,
        "--output", archivedNotesDir.get().file("release-history.json").asFile.absolutePath)
    inputs.file(rootProject.file("RELEASE_NOTES.md"))
    outputs.file(archivedNotesDir.get().file("release-history.json").asFile)
}

// Android's asset merger must finish the Copy task before reading generated assets.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(embedCurrentReleaseNotes, embedReleaseHistory)
}

// Explicit private debug-build A/B switch; normal and release builds retain
// the existing snapshot path. No version or signing change.
val atomicNativeSurfaceTrial = providers.gradleProperty("atomicNativeSurfaceTrial")
    .map { it == "true" }.getOrElse(false)
val atomicPairedRailTrial = providers.gradleProperty("atomicPairedRailTrial")
    .map { it == "true" }.getOrElse(false) && atomicNativeSurfaceTrial

android {
    buildFeatures { buildConfig = true }
    namespace = "io.github.x647368maker.atomicworkbench"
    sourceSets.getByName("main").assets.srcDir(embeddedNotesDir.get().asFile)
    sourceSets.getByName("main").assets.srcDir(archivedNotesDir.get().asFile)
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.x647368maker.atomicworkbench"
        minSdk = 35
        targetSdk = 36
        buildConfigField("boolean", "ATOMIC_NATIVE_SURFACE_TRIAL", "false")
        buildConfigField("boolean", "ATOMIC_PAIRED_RAIL_TRIAL", "false")
        versionCode = 88
        versionName = "0.9.73-dev"
        testInstrumentationRunner = "io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation"

    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".atomic-signing/atomic-development.p12")
            storeType = "PKCS12"
            storePassword = System.getenv("ATOMIC_KEYSTORE_PASSWORD")
            keyAlias = "androiddebugkey"
            keyPassword = System.getenv("ATOMIC_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ATOMIC_NATIVE_SURFACE_TRIAL", atomicNativeSurfaceTrial.toString())
            buildConfigField("boolean", "ATOMIC_PAIRED_RAIL_TRIAL", atomicPairedRailTrial.toString())
            // Missing private key permits compile/test validation, never a temporary signature.
            signingConfig = if (System.getenv("ATOMIC_SIGNING_READY") == "true") signingConfigs.getByName("debug") else null
        }
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // The framework provides this API at runtime; it must never be packaged in the APK.
    compileOnly("io.github.libxposed:api:${providers.gradleProperty("atomicXposedApi").getOrElse("102.0.0")}")

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("cn.6tail:lunar:1.7.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.github.libxposed:api:${providers.gradleProperty("atomicXposedApi").getOrElse("102.0.0")}") // JVM contract tests only; not in target APK
}
