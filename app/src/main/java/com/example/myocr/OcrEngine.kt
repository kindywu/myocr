package com.example.myocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.Text
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ML Kit 中文 OCR 引擎封装
 *
 * 特点：
 * - 捆绑模式：中文 OCR 模型直接内嵌在 APK 中
 * - 无需 Google Play Services，国产手机（华为、小米等）通用
 * - 完全离线运行，不上传图片
 * - 支持简体中文 + 混排英文/数字
 */
class OcrEngine private constructor() : AutoCloseable {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val isReleased = AtomicBoolean(false)

    /**
     * 对 Bitmap 进行 OCR 识别（同步阻塞调用，返回纯文本）
     *
     * 兼容旧接口，内部委托给 recognizeStructured()。
     */
    fun recognize(bitmap: Bitmap): String {
        return recognizeStructured(bitmap).fullText
    }

    /**
     * 对 Bitmap 进行 OCR 识别（同步阻塞调用，返回结构化结果）
     *
     * 包含逐行文本 + 位置矩形 + 置信度，便于下游按语义分析。
     */
    fun recognizeStructured(bitmap: Bitmap): OcrResult {
        if (isReleased.get()) {
            throw IllegalStateException("OcrEngine has been released")
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        val latch = CountDownLatch(1)
        var mlKitText: Text? = null
        var error: Exception? = null

        recognizer.process(image)
            .addOnSuccessListener { result ->
                mlKitText = result
                latch.countDown()
            }
            .addOnFailureListener { e ->
                error = e
                latch.countDown()
            }

        latch.await()

        if (error != null) {
            throw error!!
        }

        val result = OcrResult.fromMlKit(mlKitText!!)
        Log.d(TAG, "OCR recognized ${result.lines.size} lines, ${result.fullText.length} chars")
        return result
    }

    override fun close() {
        if (isReleased.compareAndSet(false, true)) {
            recognizer.close()
            Log.d(TAG, "OcrEngine released")
        }
    }

    companion object {
        private const val TAG = "OcrEngine"

        /**
         * 创建 OCR 引擎实例
         */
        fun create(): OcrEngine {
            Log.d(TAG, "Creating ML Kit Chinese OCR engine (bundled mode)")
            return OcrEngine()
        }
    }
}

/**
 * OCR 识别结果（结构化）
 *
 * @param fullText 全部文本（按阅读顺序拼接，每行换行分隔）
 * @param lines    逐行识别结果，按阅读顺序排列
 */
data class OcrResult(
    val fullText: String,
    val lines: List<OcrLine>
) {
    companion object {
        /**
         * 从 ML Kit Text 对象转换为结构化 OcrResult
         */
        fun fromMlKit(text: Text): OcrResult {
            val allLines = mutableListOf<OcrLine>()
            val textBuilder = StringBuilder()
            var blockIndex = 0

            // 按 textBlock（段落）遍历
            for (block in text.textBlocks) {
                if (blockIndex > 0) textBuilder.append('\n')

                for (line in block.lines) {
                    val text = line.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        allLines.add(
                            OcrLine(
                                text = text,
                                boundingBox = line.boundingBox?.let { Rect(it) },
                                confidence = line.confidence ?: 0f
                            )
                        )
                        textBuilder.append(text).append('\n')
                    }
                }
                blockIndex++
            }

            // 去掉末尾多余的换行
            var fullText = textBuilder.toString().trim()
            if (fullText.isEmpty()) {
                fullText = text.text?.trim() ?: ""
            }

            return OcrResult(fullText = fullText, lines = allLines)
        }
    }
}

/**
 * OCR 单行识别结果
 *
 * @param text        该行文字
 * @param boundingBox 该行在原始图片上的位置矩形（像素坐标）
 * @param confidence  识别置信度（0.0 ~ 1.0）
 */
data class OcrLine(
    val text: String,
    val boundingBox: Rect? = null,
    val confidence: Float = 0f
) {
    /**
     * 根据 boundingBox 的 Y 坐标估算行在图片中的垂直位置
     *
     * @return "上方" | "中上方" | "中部" | "中下方" | "下方" | "未知"
     */
    fun estimateVerticalPosition(imageHeight: Int): String {
        if (boundingBox == null || imageHeight <= 0) return "未知"
        val centerY = boundingBox.centerY().toFloat() / imageHeight
        return when {
            centerY < 0.2f -> "上方"
            centerY < 0.4f -> "中上方"
            centerY < 0.6f -> "中部"
            centerY < 0.8f -> "中下方"
            else -> "下方"
        }
    }
}
