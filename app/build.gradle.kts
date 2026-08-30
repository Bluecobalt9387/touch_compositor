plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.touchcompositor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.touchcompositor.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
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
