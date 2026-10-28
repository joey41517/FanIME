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
    // EmojiPanel 要捲動選擇器內部的 RecyclerView；版本和 emojipicker 帶進來的一致
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    testImplementation("junit:junit:4.13.2")
}
