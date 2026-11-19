plugins { id("com.android.application") }
val revision = providers.environmentVariable("GITHUB_SHA").getOrElse("unknown")
    .takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"
android {
    namespace = "workbench2.hostruntime"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "io.github.x647368maker.atomicworkbench.hostruntime"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "2.0-host-runtime-1"
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
            signingConfig = null
            optimization { enable = false }
        }
    }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
