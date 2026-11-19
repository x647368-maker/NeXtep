plugins { id("com.android.application") }

val revision = providers.environmentVariable("GITHUB_SHA").getOrElse("unknown")
    .takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"

android {
    namespace = "workbench2.field"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        // Diagnostic companion ONLY. Never replaces the real app or shares its UID/data.
        applicationId = "io.github.x647368maker.atomicworkbench.hostevidence"
        minSdk = 35
        targetSdk = 36
        versionCode = 4
        versionName = "2.0-framework-export-1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SOURCE_SHA", "\"$revision\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            // CI verifies and applies original signing outside Gradle after runtime gates.
            signingConfig = null
            optimization { enable = false }
        }
    }
}
dependencies {
    implementation(project(":"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
