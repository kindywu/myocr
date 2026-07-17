import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 从 secrets.properties 或 local.properties 读取 DeepSeek API Key
// 优先级：secrets.properties > local.properties > gradle property
val deepseekApiKey: String = run {
    val props = Properties()
    // 1. 尝试 secrets.properties
    val secretsFile = rootProject.file("secrets.properties")
    if (secretsFile.exists()) {
        secretsFile.inputStream().use { props.load(it) }
    }
    // 2. 尝试 local.properties（覆盖）
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { props.load(it) }
    }
    props.getProperty("deepseekApiKey")?.trim()
        ?: project.findProperty("deepseekApiKey")?.toString()
        ?: "<test>"
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

        // DeepSeek API Key — 从 local.properties 注入，构建时写入 BuildConfig
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${deepseekApiKey}\"")

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
        buildConfig = true
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

    // PP-OCRv6 ONNX Runtime - 纯本地 OCR，不依赖 Google Play Services
    implementation(libs.onnxruntime.android)

    // Fragment KTX - 用于药品录入流程的多页面导航
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    testImplementation(libs.junit)
    // org.json 在 Android 上内置，但 local JUnit 需要单独依赖
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}