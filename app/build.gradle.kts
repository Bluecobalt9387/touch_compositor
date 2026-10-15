plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    buildFeatures { aidl = true }
    namespace = "dev.touchcompositor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.touchcompositor.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0-routing"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(project(":core-input"))
    implementation(project(":shell-helper"))
}
