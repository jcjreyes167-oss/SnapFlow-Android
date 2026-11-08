plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.snapflow.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.snapflow.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1"
    }
}
