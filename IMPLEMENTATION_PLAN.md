# 实施计划：Google ML Kit 捆绑模式拍照 + OCR

## 完成状态 ✅

所有修改已完成。

## 技术选型

| 组件 | 方案 | 说明 |
|------|------|------|
| **相机** | CameraX (AndroidX) | 无 GMS 依赖 |
| **OCR** | `com.google.mlkit:text-recognition-chinese` 捆绑模式 | 模型内嵌 APK，不依赖 Google Play Services |
| **UI** | Material Design 3 + CoordinatorLayout | |

## ML Kit 捆绑模式原理

- `text-recognition-chinese` 依赖将中文 OCR 模型（~14MB）静态打包到 APK 中
- 运行时直接从本地加载，无需请求 Google Play Services
- 国产手机（华为、小米、OPPO、vivo 等无 GMS 设备）完全可用

## 文件变更记录

| 操作 | 文件 | 说明 |
|------|------|------|
| ✅ 修改 | `app/build.gradle.kts` | 替换 tess-two → ML Kit `text-recognition-chinese:16.0.0` |
| ✅ 重写 | `app/src/main/java/.../OcrEngine.kt` | 封装 ML Kit `ChineseTextRecognizerOptions` |
| ✅ 重写 | `app/src/main/java/.../MainActivity.kt` | 替换 Tesseract API → ML Kit，移除图像预处理 |
| ✅ 保持 | `app/src/main/res/layout/activity_main.xml` | 布局不变（CameraX + 结果卡片 + 拍照按钮） |
| ✅ 删除 | `app/src/main/assets/tessdata/` | 不再需要 Tesseract 数据 |
| ✅ 新建 | `app/proguard-rules.pro` | ML Kit 混淆规则 |
| ✅ 更新 | `app/build.gradle.kts` | 移除 coroutines 依赖、移除 noCompress |

## 核心 API 变更

旧（Tesseract）：
```kotlin
val api = TessBaseAPI()
api.init(dataPath, "chi_sim")
api.setImage(bitmap)
val text = api.utf8Text
```

新（ML Kit 捆绑）：
```kotlin
val recognizer = TextRecognition.getClient(
    ChineseTextRecognizerOptions.Builder().build()
)
val image = InputImage.fromBitmap(bitmap, 0)
recognizer.process(image)
    .addOnSuccessListener { result -> text = result.text }
```

## 应用流程

```
App 启动
  ├→ 请求相机权限（运行时）
  ├→ CameraX 预览（back/front 切换）
  ├→ ML Kit OCR 引擎初始化（后台线程，创建即用）
  │
  用户点击拍照按钮
  ├→ CameraX ImageCapture 保存 JPEG
  ├→ 后台线程：BitmapFactory.decode → OcrEngine.recognize()
  │   └→ ML Kit ChineseTextRecognizer.process() → CountDownLatch 等待
  ├→ UI 线程：显示结果卡片
  └→ 支持复制文本到剪贴板
```

## 国产手机兼容性

| 品牌 | 是否可用 | 备注 |
|------|----------|------|
| 华为 HarmonyOS | ✅ | 无 GMS，ML Kit 捆绑模式可用 |
| 小米 HyperOS | ✅ | 无 GMS 依赖 |
| OPPO ColorOS | ✅ | |
| vivo OriginOS | ✅ | |
| 荣耀 MagicOS | ✅ | |
| 三星 One UI | ✅ | 有 GMS，但本地模式不依赖 |
