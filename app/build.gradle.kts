import java.util.Properties
import java.io.File

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

// ==================== 16 KB page size 对齐 ====================
// Google Play 要求 2025-11 起，target Android 15+ 的应用必须支持 16 KB 页
// 用 NDK llvm-objcopy 修改预编译 .so 的 section alignment（如 ONNX Runtime）

fun findNdkDir16k(): File {
    val sdkProp = Properties()
    rootProject.file("local.properties").inputStream().use { sdkProp.load(it) }
    val sdkPath = sdkProp.getProperty("sdk.dir").replace("\\", "/")
    val ndkDir = File("$sdkPath/ndk")
    val ndkFiles: Array<File>? = ndkDir.listFiles()
    val versions: List<File> = ndkFiles?.filter { it.isDirectory }?.sortedByDescending { it.name }
        ?: error("NDK not found in $ndkDir")
    return versions.first()
}
val ndkDir_16k = findNdkDir16k()
val buildDir_16k = layout.buildDirectory.asFile.get()
val scriptFile_16k = File(rootProject.projectDir, "tools/realign_16kb.sh")

val ndkPath_16k = ndkDir_16k.absolutePath
val buildPath_16k = buildDir_16k.absolutePath
val scriptPath_16k = scriptFile_16k.absolutePath

// 用 Exec 任务类型，原生支持配置缓存序列化
// Windows 路径转 POSIX（供 Git Bash 使用）
fun winPath(p: String) = p.replace("\\", "/")
fun posix(p: String) = "/" + p.replace("\\", "/").replace(":", "").lowercase()
val realignTask = tasks.register<Exec>("realignDebugNativeLibs") {
    dependsOn("mergeDebugNativeLibs")
    // 用 Git Bash POSIX 路径 + /bin/bash（含 MSYS2 路径转换）
    commandLine(
        winPath("C:/Program Files/Git/bin/bash"),
        posix(scriptPath_16k), posix(ndkPath_16k), posix(buildPath_16k)
    )
    isIgnoreExitValue = true
}

// packageDebug 依赖它（配置缓存安全的方式）
tasks.matching { it.name == "packageDebug" }.configureEach {
    dependsOn(realignTask)
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