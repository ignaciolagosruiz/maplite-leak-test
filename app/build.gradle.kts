plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.chicho.mapleak"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.chicho.mapleak"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Same Maps SDK version as uTag (gradle/libs.versions.toml: playServicesMaps = "19.1.0")
    implementation("com.google.android.gms:play-services-maps:19.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
