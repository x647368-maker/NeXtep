plugins { id("com.android.application") version "9.3.0" }
android {
    namespace = "workbench2.nativelab"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "workbench2.nativelab"
        minSdk = 35
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":engine"))
    compileOnly(files(requireNotNull(System.getenv("WB2_CAR_STUB"))))
    androidTestCompileOnly(files(requireNotNull(System.getenv("WB2_CAR_STUB"))))
    androidTestImplementation(files(requireNotNull(System.getenv("WB2_IDENTITY_HELPER"))))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
