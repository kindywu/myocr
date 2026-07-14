# MyOCR

MyOCR 是一个面向 Android 设备的离线 OCR 工具，支持拍照识别、相册选图识别、文本复制、语音朗读，以及将识别结果通过局域网上传到 PC 端接收服务。

## 项目目标

- 在 Android 端完成中文文字识别，不依赖 Google Play Services
- 在识别完成后提供复制、朗读与上传能力
- 在 PC 端通过轻量 Go 服务接收并保存 OCR 结果

## 仓库结构

```text
.
|-- app/                  Android 应用模块
|   |-- src/main/java/com/example/myocr/
|   |   |-- MainActivity.kt
|   |   |-- OcrEngine.kt
|   |   `-- OcrUploader.kt
|   `-- src/main/res/     布局、文案与图标资源
|-- server/
|   `-- main.go           OCR 结果接收服务
|-- docs/                 项目文档
|-- IMPLEMENTATION_PLAN.md
`-- gradlew
```

## 技术栈

- Android: Kotlin、ViewBinding、Material 组件、CameraX
- OCR: Google ML Kit 中文识别 bundled 模式
- TTS: Android `TextToSpeech`，并提供基于系统 Intent 的兼容回退
- 上传: `HttpURLConnection` + JSON POST
- 服务端: Go 标准库 `net/http`

## 核心能力

- 拍照识别
- 相册识别
- 识别结果复制
- 识别结果朗读
- OCR 文本上传到 PC
- 服务端按文件落盘并追加聚合日志

## 快速启动

### 1. 启动 PC 接收服务

在仓库根目录执行：

```bash
cd server
go run main.go
```

默认监听 `8080` 端口，也可以指定端口：

```bash
go run main.go 8081
```

服务启动后会打印局域网地址，例如 `http://192.168.1.100:8080`。

### 2. 运行 Android 应用

- 使用 Android Studio 打开仓库根目录 `/workspace`
- 连接 Android 真机
- 运行 `:app` 模块

也可以命令行构建：

```bash
./gradlew assembleDebug
```

### 3. 联调上传

- 确保手机与 PC 在同一局域网
- 在 App 中输入服务器地址，例如 `192.168.1.100:8080`
- 完成 OCR 后点击“上传”
- 服务端会将结果保存到 `server/ocr_results/`

## 使用流程

1. 首次启动后授予相机权限
2. 点击拍照按钮或从相册选择图片
3. 等待 OCR 识别完成
4. 对结果进行复制、朗读或上传
5. 在 PC 端查看接收到的文本文件

## 已知约束

- `minSdk` 当前为 `35`，支持设备范围较窄
- Android 端默认使用明文 HTTP，适合局域网调试场景
- Go 服务当前为轻量单文件实现，未提供部署脚本和持久化策略
- 测试代码仍以模板为主，验收主要依赖人工联调

## 文档导航

- [环境与运行说明](docs/setup-and-run.md)
- [接口规范](docs/api-spec.md)
- [Android 应用说明](docs/android-app-guide.md)

## 关键文件

- Android 入口：`app/src/main/java/com/example/myocr/MainActivity.kt`
- OCR 封装：`app/src/main/java/com/example/myocr/OcrEngine.kt`
- 上传逻辑：`app/src/main/java/com/example/myocr/OcrUploader.kt`
- 服务端入口：`server/main.go`
