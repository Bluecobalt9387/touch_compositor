plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.touchcompositor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.touchcompositor.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-beta.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-adb"))
    implementation(project(":core-input"))
    implementation(project(":shell-helper"))
}
