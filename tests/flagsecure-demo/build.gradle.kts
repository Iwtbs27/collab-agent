plugins {
    id("com.android.application")
}

android {
    namespace = "ai.opencyvis.test.flagsecure"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.opencyvis.test.flagsecure"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }
}
