plugins {
    id("com.android.application")
}

android {
    namespace = "com.unexpectcat.omnitrix.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unexpectcat.omnitrix.watchface"
        // Watch Face Format v2 (Launch, Gyro) needs Wear OS 4.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
