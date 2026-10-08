plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Mỗi lần GitHub build, số này tự tăng -> cài đè được bản cũ.
val runNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.macrosniper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.macrosniper.app"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Khóa ký CỐ ĐỊNH: mọi bản build đều cùng chữ ký nên Android cho cập nhật đè,
    // không bắt gỡ app cũ nữa.
    signingConfigs {
        create("fixed") {
            storeFile = file("macrosniper.keystore")
            storePassword = "macrosniper"
            keyAlias = "macrosniper"
            keyPassword = "macrosniper"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Máy khách ADB tự nhúng cho chế độ "Gỡ lỗi WiFi" (không cần app ngoài)
    implementation("com.github.MuntashirAkon:libadb-android:1.0.1")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
