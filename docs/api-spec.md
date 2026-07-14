# 接口规范

MyOCR 的 PC 接收服务使用 HTTP 提供健康检查与 OCR 文本接收能力。当前服务由 `server/main.go` 提供。

## 接口概览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/health` | 健康检查 |
| `POST` | `/upload` | 上传 OCR 结果 |
| `GET` | `/` | 简单首页，显示服务状态与接口说明 |

## 通用说明

- 服务默认监听端口：`8080`
- 服务端启用 CORS，允许 `GET`、`POST`、`OPTIONS`
- 允许的请求头包括：`Content-Type`、`X-Device`

## `GET /health`

### 说明

用于检查服务是否在线。

### 请求示例

```bash
curl http://127.0.0.1:8080/health
```

### 成功响应

```json
{
  "status": "ok",
  "time": "2026-07-14T10:15:30+08:00",
  "version": "1.0",
  "endpoints": ["/health", "/upload"]
}
```

## `POST /upload`

### 说明

接收 Android 端上传的 OCR 结果，保存为单独文本文件，并同步追加到聚合日志。

### 支持的请求格式

#### 1. JSON

请求头：

```text
Content-Type: application/json; charset=utf-8
X-Device: Xiaomi 14
```

请求体：

```json
{
  "text": "你好，世界",
  "device": "Xiaomi 14",
  "time": "2026-07-14 10:15:30",
  "filename": "ocr_custom.txt"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|------|------|------|
| `text` | 是 | OCR 识别结果 |
| `device` | 否 | 设备名称 |
| `time` | 否 | 结果生成时间 |
| `filename` | 否 | 自定义文件名 |

#### 2. 纯文本

请求头：

```text
Content-Type: text/plain
X-Device: Xiaomi 14
```

请求体直接写入 OCR 文本内容。

#### 3. 自动检测

如果未明确提供支持的 `Content-Type`，服务会尝试先按 JSON 解析，失败后按纯文本处理。

### Android 客户端实际行为

当前 Android 端上传逻辑为：

- 自动将用户输入补全为 `http://<server>/upload`
- 使用 `application/json; charset=utf-8`
- 同时在 JSON 体和 `X-Device` 请求头中写入设备信息
- 超时时间为 5 秒

当前 JSON 体示例：

```json
{
  "text": "识别出来的文本",
  "device": "HUAWEI Pura 70",
  "time": "2026-07-14 10:15:30"
}
```

### 请求示例

```bash
curl -X POST http://127.0.0.1:8080/upload \
  -H 'Content-Type: application/json' \
  -d '{"text":"你好世界","device":"Pixel 9","time":"2026-07-14 10:15:30"}'
```

纯文本示例：

```bash
curl -X POST http://127.0.0.1:8080/upload \
  -H 'Content-Type: text/plain' \
  -H 'X-Device: Pixel 9' \
  --data '你好世界'
```

### 成功响应

```json
{
  "status": "ok",
  "message": "接收成功",
  "filename": "ocr_20260714_101530.txt",
  "length": 4
}
```

### 失败响应

常见失败情况：

| HTTP 状态码 | 场景 | 响应示例 |
|-------------|------|----------|
| `400` | JSON 解析失败 | `JSON 解析失败: ...` |
| `400` | 请求体为空 | `文本内容为空` |
| `405` | 非 `POST` 请求 | `仅支持 POST 请求` |
| `500` | 文件保存失败 | `保存失败` |

## 落盘规则

上传成功后，服务端会：

- 在 `ocr_results/` 目录生成单独结果文件
- 结果文件默认命名为 `ocr_yyyyMMdd_HHmmss.txt`
- 同时向 `_all_results.log` 追加一条聚合日志

单次结果文件内容示例：

```text
=== OCR 结果 ===
时间: 2026-07-14 10:15:30
设备: Pixel 9
来源 IP: 192.168.1.10:51822
================

你好世界
```

## 兼容性说明

- 当前 Android 端默认使用明文 HTTP，适用于局域网环境
- 若后续改为 HTTPS，需要同步调整客户端地址配置与证书方案
- 当前服务未引入鉴权机制，不建议直接暴露到公网
