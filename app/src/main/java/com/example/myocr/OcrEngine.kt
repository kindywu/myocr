package com.example.myocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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
     * 对 Bitmap 进行 OCR 识别（同步阻塞调用）
     *
     * ML Kit 的 process() 是异步的，此方法通过阻塞等待封装为同步调用，
     * 便于在协程/后台线程中使用。
     *
     * @param bitmap 待识别的位图
     * @return 识别到的文本，没有则返回空字符串
     */
    fun recognize(bitmap: Bitmap): String {
        if (isReleased.get()) {
            throw IllegalStateException("OcrEngine has been released")
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText = ""
        var error: Exception? = null

        recognizer.process(image)
            .addOnSuccessListener { result ->
                resultText = result.text.trim()
                latch.countDown()
            }
            .addOnFailureListener { e ->
                error = e
                latch.countDown()
            }

        // 阻塞等待结果（应在后台线程调用）
        latch.await()

        if (error != null) {
            throw error!!
        }

        Log.d(TAG, "OCR recognized ${resultText.length} characters")
        return resultText
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
         *
         * ML Kit 捆绑模式无需初始化 traineddata，此方法直接返回实例。
         */
        fun create(): OcrEngine {
            Log.d(TAG, "Creating ML Kit Chinese OCR engine (bundled mode)")
            return OcrEngine()
        }
    }
}
