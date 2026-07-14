package com.example.myocr

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OCR 结果上传工具类
 *
 * 将识别到的文本通过 HTTP POST 发送到局域网内的 PC 服务器。
 * 使用 HttpURLConnection 而非 OkHttp，避免引入额外依赖。
 */
object OcrUploader {

    private const val TAG = "OcrUploader"
    private const val TIMEOUT_MS = 5000

    /**
     * 上传结果
     */
    data class UploadResult(
        val success: Boolean,
        val message: String
    )

    /**
     * 上传 OCR 文本到服务器
     *
     * @param serverUrl 服务器完整 URL，如 http://192.168.1.100:8080/upload
     * @param text OCR 识别文本
     * @return 上传结果
     */
    fun upload(serverUrl: String, text: String): UploadResult {
        if (text.isBlank()) {
            return UploadResult(false, "文本为空，无需上传")
        }

        return try {
            val url = URL(serverUrl.trimEnd('/') + "/upload")
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Device", getDeviceInfo())
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }

            // 构建 JSON body
            val json = JSONObject().apply {
                put("text", text)
                put("device", getDeviceInfo())
                put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
            }

            // 写入请求体
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            // 读取响应
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            connection.disconnect()

            if (responseCode in 200..299) {
                Log.d(TAG, "上传成功: $responseBody")
                UploadResult(true, "上传成功")
            } else {
                Log.w(TAG, "上传失败 ($responseCode): $responseBody")
                UploadResult(false, "服务器返回 $responseCode")
            }

        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "连接失败: ${e.message}")
            UploadResult(false, "连接失败，请检查服务器地址和网络")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "连接超时: ${e.message}")
            UploadResult(false, "连接超时，请检查服务器是否启动")
        } catch (e: Exception) {
            Log.e(TAG, "上传异常: ${e.message}", e)
            UploadResult(false, "上传失败: ${e.message}")
        }
    }

    /**
     * 获取设备名称用于标识
     */
    private fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /**
     * 验证 URL 格式是否合法
     */
    fun isValidUrl(url: String): Boolean {
        return try {
            val u = URL(url)
            u.protocol == "http" || u.protocol == "https"
        } catch (e: Exception) {
            false
        }
    }
}
