# Android 应用说明

本文档说明 MyOCR Android 端的页面结构、主要功能、关键类职责，以及 OCR、上传、TTS 的实现方式。

## 应用概览

MyOCR 是一个单 Activity 应用，主流程由 `MainActivity` 统一编排，围绕以下能力展开：

- CameraX 相机预览与拍照
- 相册选图
- ML Kit 中文 OCR
- 结果复制
- 结果朗读
- 结果上传到局域网 PC 服务

## 页面结构

主页面由以下区域组成：

- 相机预览区
- 顶部工具栏
- 识别结果卡片
- 服务器地址输入卡片
- OCR 处理中进度条
- 底部操作按钮区

### 识别结果卡片

识别成功后显示：

- OCR 文本内容
- 复制按钮
- 朗读按钮
- 上传按钮
- 上传状态提示

当未识别到文字时：

- 展示“未识别到文字”
- 隐藏朗读按钮
- 隐藏上传按钮

## 关键类职责

### `MainActivity`

负责应用主流程编排，包括：

- 初始化界面事件
- 申请相机权限
- 启动 CameraX 预览
- 初始化 OCR 引擎
- 初始化 TTS 引擎
- 响应拍照与相册选择
- 在后台线程执行 OCR 与上传
- 更新朗读、上传与结果展示状态

### `OcrEngine`

负责封装 ML Kit 中文 OCR：

- 创建 `ChineseTextRecognizerOptions`
- 接收 `Bitmap` 并执行识别
- 通过 `CountDownLatch` 将异步接口封装为后台线程中的同步调用
- 在 `close()` 中释放识别器资源

### `OcrUploader`

负责上传 OCR 文本：

- 校验地址格式
- 构造 `POST /upload` 请求
- 使用 JSON 发送 `text`、`device`、`time`
- 返回统一的上传结果对象

## 初始化流程

应用启动时主要执行以下步骤：

1. 绑定视图并注册按钮事件
2. 读取并恢复上次保存的服务器地址
3. 初始化 TTS
4. 初始化 OCR 引擎
5. 检查相机权限并启动相机

## OCR 实现说明

### 技术方案

- 使用 `com.google.mlkit:text-recognition-chinese`
- 采用 bundled 模式，模型打包进 APK
- 不依赖 Google Play Services
- 适合国产 Android 机型

### 拍照识别流程

1. 用户点击拍照按钮
2. `ImageCapture` 将照片写入缓存文件
3. OCR 线程读取图片并解码为 `Bitmap`
4. `OcrEngine.recognize()` 执行识别
5. UI 线程展示识别结果并恢复按钮状态

### 相册识别流程

1. 用户点击相册按钮
2. 系统图片选择器返回 `content://` URI
3. 应用将 URI 内容复制到临时文件
4. 复用拍照后的 OCR 处理流程

### 结果展示规则

- 文本为空时显示“未识别到文字”
- 文本非空时记录到 `currentOcrText`
- 识别成功后显示朗读与上传按钮
- 识别完成后触发一次轻触感反馈

## 上传实现说明

### 服务器地址

- 地址输入框会自动保存到 `SharedPreferences`
- 支持输入 `192.168.1.100:8080`
- 也支持输入完整地址 `http://192.168.1.100:8080`
- 若未指定协议，客户端会自动补全为 `http://`

### 上传流程

1. 从 `currentOcrText` 读取当前识别结果
2. 校验服务器地址格式
3. 将按钮切换到“上传中”状态
4. 在后台线程调用 `OcrUploader.upload()`
5. 根据结果更新状态文案和 Snackbar

### 上传请求内容

当前上传 JSON 包含：

- `text`: OCR 文本
- `device`: 设备厂商与型号
- `time`: 本地时间字符串

## TTS 说明

### 功能目标

TTS 用于将 OCR 识别出的文字直接朗读出来，便于快速确认内容，或在不方便看屏幕时听取结果。

### 初始化方式

应用启动时调用 `initTts()`：

- 若已有实例，先执行 `shutdown()`
- 新建 `TextToSpeech`
- 设置语言为 `Locale.CHINESE`
- 设置语速和音调为 `1.0`
- 记录引擎与语言可用性日志

### 朗读入口

- 识别结果不为空时显示“朗读”按钮
- 点击按钮时进入 `toggleSpeak()`
- 若当前正在朗读，则执行停止
- 若当前未朗读，则执行开始朗读

### 标准朗读流程

当系统标准 TTS 能支持中文时：

1. 注册 `UtteranceProgressListener`
2. 调用 `tts.speak(...)`
3. 在 `onStart` 中切换按钮为“停止”
4. 在 `onDone`、`onStop`、`onError` 中恢复按钮为“朗读”

### 兼容回退策略

某些 ROM 对标准 `TextToSpeech` 支持不完整，项目实现了回退逻辑：

- 若默认引擎为空，或中文语言不可用，则改用系统 `SPEAK` Intent
- 若 `tts.speak(...)` 返回错误，也回退到 Intent 方式
- 若 Intent 仍不可用，则弹出提示框，引导用户：
  - 打开 TTS 设置
  - 安装语音数据
  - 检查默认引擎与中文发音能力

### 生命周期处理

- `onPause()` 中若正在朗读，则主动停止
- `onResume()` 中若 TTS 尚未就绪，则尝试重新初始化
- `onDestroy()` 中执行 `tts?.shutdown()`

### TTS 使用前提

- 系统存在可用的 TTS 引擎
- 已安装中文语音数据
- ROM 允许标准 TTS 或 Intent 方式工作

### 常见问题

#### 点击朗读没有声音

- 检查媒体音量
- 检查系统 TTS 默认引擎
- 确认中文语音数据已安装

#### 某些 ROM 无法通过标准 API 朗读

- 项目会自动回退到 Intent 方式
- 若仍失败，需要手动在系统设置中启用语音服务

#### 从后台返回后朗读异常

- 应用会在 `onResume()` 中尝试恢复 TTS
- 若问题持续，建议重新打开应用

## 权限与系统能力

### 需要的权限

- `CAMERA`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`

### 包可见性

Manifest 中通过 `queries` 声明了 `android.intent.action.TTS_SERVICE`，用于 Android 11+ 的 TTS 引擎发现。

### 网络策略

应用启用了 `usesCleartextTraffic=true`，用于局域网 HTTP 上传。

## 线程模型

应用使用三个单线程执行器：

- `cameraExecutor`: 相机拍照回调
- `ocrExecutor`: OCR 初始化与识别
- `uploadExecutor`: 上传请求

这种划分可以避免相机、识别和网络操作相互阻塞。

## 已知约束

- 当前仅支持 Android `minSdk 35`
- 暂未提供批量识别、多页识别或历史记录浏览
- 当前 TTS 兼容性依赖系统 ROM 与语音引擎实现
- NDK 构建配置仍存在，但当前 OCR 主流程并未使用原生代码
