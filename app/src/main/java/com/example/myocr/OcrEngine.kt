package com.example.myocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.myocr.ocr.CharDictLoader
import com.example.myocr.ocr.CTCDecoder
import com.example.myocr.ocr.DBPostProcessor
import com.example.myocr.ocr.DetPreprocessor
import com.example.myocr.ocr.RecPreprocessor
import java.io.File
import java.nio.FloatBuffer

/**
 * PP-OCRv6 ONNX Small OCR 引擎 — 替换 Google ML Kit 中文 OCR
 *
 * 特点：
 * - PP-OCRv6 Small 模型（精度优先），检测 + 识别二阶段流水线
 * - ONNX Runtime 纯本地推理，无需 Google Play Services
 * - 完全离线运行，不上传图片
 * - 支持简体中文 + 混排英文/数字
 */
class OcrEngine private constructor(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detSession: OrtSession
    private val recSession: OrtSession
    private val charDict: List<String>

    init {
        Log.d(TAG, "Loading PP-OCRv6 Small ONNX models...")

        // 从 assets 复制模型到缓存目录（ORT 需要文件路径）
        detSession = env.createSession(
            loadModelFile(context, "model/ppocrv6_onnx/small_det/inference.onnx"),
            OrtSession.SessionOptions(),
        )
        recSession = env.createSession(
            loadModelFile(context, "model/ppocrv6_onnx/small_rec/inference.onnx"),
            OrtSession.SessionOptions(),
        )
        charDict = CharDictLoader.load(context, "model/ppocrv6_onnx/small_rec/inference.yml")

        Log.d(TAG, "PP-OCRv6 Small ONNX engine ready (det=${detSession.inputNames}, rec=${recSession.inputNames})")
    }

    /** 从 assets 拷贝 ONNX 到缓存目录，ORT 需要文件路径 */
    private fun loadModelFile(context: Context, assetPath: String): String {
        val cacheFile = File(context.cacheDir, assetPath)
        if (!cacheFile.exists()) {
            cacheFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Copied $assetPath to cache (${cacheFile.length()} bytes)")
        }
        return cacheFile.absolutePath
    }

    /** float[] → FloatBuffer（供 OnnxTensor.createTensor 使用） */
    private fun floatArrayToBuffer(data: FloatArray): FloatBuffer = FloatBuffer.wrap(data)

    // ── 公开方法 ────────────────────────────────────────

    /**
     * 对 Bitmap 进行 OCR 识别（同步阻塞调用，返回纯文本）
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
        val startTime = System.currentTimeMillis()
        val origW = bitmap.width
        val origH = bitmap.height

        Log.d(TAG, "Starting OCR on ${origW}x${origH} bitmap")

        // 1. 检测阶段 — 找文字区域
        val detResult = DetPreprocessor.process(bitmap)
        val detShape = longArrayOf(1, 3, detResult.height.toLong(), detResult.width.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatArrayToBuffer(detResult.inputTensor), detShape)

        val detOut = detSession.run(mapOf(detSession.inputNames.iterator().next() to inputTensor))
        val probTensor = detOut.get(0) as OnnxTensor
        val probShape = probTensor.info.shape
        val oH = probShape[2].toInt()
        val oW = probShape[3].toInt()

        val probArray = FloatArray(oH * oW)
        probTensor.floatBuffer.rewind()
        probTensor.floatBuffer.get(probArray)

        val boxes = DBPostProcessor.process(
            probMap = probArray,
            probW = oW,
            probH = oH,
            origW = origW,
            origH = origH,
            scaleX = detResult.scaleX,
            scaleY = detResult.scaleY,
        )

        Log.d(TAG, "Detection found ${boxes.size} text regions")

        // 2. 识别阶段 — 对每个文字区域做识别
        val lines = mutableListOf<OcrLine>()
        val textBuilder = StringBuilder()

        for (box in boxes) {
            val x1 = box[0]; val y1 = box[1]; val x2 = box[2]; val y2 = box[3]
            val bw = x2 - x1 + 1
            val bh = y2 - y1 + 1
            if (bw < 4 || bh < 4) continue

            val cx = x1.coerceIn(0, origW - 1)
            val cy = y1.coerceIn(0, origH - 1)
            val cw = bw.coerceAtMost(origW - cx)
            val ch = bh.coerceAtMost(origH - cy)
            if (cw < 4 || ch < 4) continue

            try {
                val crop = Bitmap.createBitmap(bitmap, cx, cy, cw, ch)
                val recInput = RecPreprocessor.process(crop)
                val recShape = longArrayOf(1, 3, 48, 320)
                val recTensor = OnnxTensor.createTensor(env, floatArrayToBuffer(recInput.inputTensor), recShape)

                val recOut = recSession.run(mapOf(recSession.inputNames.iterator().next() to recTensor))
                val recTensorOut = recOut.get(0) as OnnxTensor
                val shape = recTensorOut.info.shape
                val tSteps = shape[1].toInt()
                val vSize = shape[2].toInt()

                val outData = FloatArray(tSteps * vSize)
                recTensorOut.floatBuffer.rewind()
                recTensorOut.floatBuffer.get(outData)

                val decoded = CTCDecoder.decode(outData, tSteps, vSize, charDict)
                if (decoded.isNotEmpty()) {
                    val r = decoded[0]
                    if (r.score >= 0.3f && r.text.isNotBlank()) {
                        lines.add(
                            OcrLine(
                                text = r.text,
                                boundingBox = Rect(x1, y1, x2, y2),
                                confidence = r.score,
                            )
                        )
                        textBuilder.append(r.text).append('\n')
                    }
                }

                crop.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Recognition failed for a region", e)
            }
        }

        // 按从上到下、从左到右排序
        lines.sortWith(compareBy({ it.boundingBox?.top }, { it.boundingBox?.left }))

        val fullText = textBuilder.toString().trimEnd('\n')
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "OCR completed in ${elapsed}ms, ${lines.size} lines, ${fullText.length} chars")

        return OcrResult(fullText = fullText, lines = lines)
    }

    override fun close() {
        try {
            detSession.close()
            recSession.close()
            Log.d(TAG, "ONNX sessions closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX sessions", e)
        }
    }

    companion object {
        private const val TAG = "OcrEngine"

        /**
         * 创建 OCR 引擎实例
         *
         * @param context Android Context（用于加载 assets 中的模型文件）
         */
        fun create(context: Context): OcrEngine {
            Log.d(TAG, "Creating PP-OCRv6 ONNX Small engine")
            return OcrEngine(context)
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
)

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
