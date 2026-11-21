plugins { id("com.android.application") version "9.3.0" }
android {
    namespace = "workbench2.oemlab"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig { applicationId = "workbench2.oemlab"; minSdk = 35; targetSdk = 35 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
