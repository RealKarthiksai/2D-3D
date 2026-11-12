plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.capturekit.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.capturekit.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":capturekit"))
}
