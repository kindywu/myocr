plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myocr"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myocr"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 仅保留 arm64-v8a 减小体积（国产手机主流架构）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // CameraX - 无 GMS 依赖，国产手机通用
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit 中文 OCR - 捆绑模式，模型内嵌 APK，无需 GMS
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // Fragment KTX - 用于药品录入流程的多页面导航
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    testImplementation(libs.junit)
    // org.json 在 Android 上内置，但 local JUnit 需要单独依赖
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}