plugins {
    id("com.android.application")
}

android {
    namespace = "tw.fanime"
    compileSdk = 37

    defaultConfig {
        applicationId = "tw.fanime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.emoji2:emoji2-emojipicker:1.7.0")
}
