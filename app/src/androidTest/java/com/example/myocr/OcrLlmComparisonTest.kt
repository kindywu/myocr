package com.example.myocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myocr.drugentry.DeepSeekClient
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * 模拟器上运行的 OCR → LLM 对比测试
 *
 * 流程：
 * 1. 从 assets/image/ 加载测试图片（full_fields.jpg / date_field.jpg）
 * 2. OcrEngine（PP-OCRv6）本地 OCR → 原始文本
 * 3. DeepSeek API → 结构化药品信息
 * 4. Logcat 输出，可用 `adb logcat -s OCR_TEST` 查看
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OcrLlmComparisonTest {

    private lateinit var ocrEngine: OcrEngine
    private var deepSeekClient: DeepSeekClient? = null
    private lateinit var context: android.content.Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        ocrEngine = OcrEngine.create(context)
        deepSeekClient = DeepSeekClient.create(context)
        Log.i(TAG, "=== OcrEngine ready, DeepSeekClient: ${if (deepSeekClient != null) "configured" else "NOT configured (skip LLM)"} ===")
    }

    @After
    fun teardown() {
        ocrEngine.close()
    }

    @Test
    fun test1_full_fields() {
        val bitmap = loadBitmap("image/full_fields.jpg")!!
        val testName = "full_fields.jpg"

        Log.i(TAG, "╔════════════════════════════════════════════════╗")
        Log.i(TAG, "║  $testName")
        Log.i(TAG, "╚════════════════════════════════════════════════╝")

        // ── Step 1: OCR ──
        val ocrResult = ocrEngine.recognizeStructured(bitmap)
        Log.i(TAG, "【OCR 原始输出】━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, ocrResult.fullText)
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // ── Step 2: LLM 结构化提取 ──
        val client = deepSeekClient
        if (client != null) {
            val llmResult = client.extractDrugInfo(
                rawText = ocrResult.fullText,
                ocrLines = ocrResult.lines,
                voiceInputDrugName = ""
            )
            Log.i(TAG, "【LLM 提取结果】━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.i(TAG, "药品名称: ${llmResult.drugInfo.drugName}")
            Log.i(TAG, "有效期至: ${llmResult.drugInfo.expiryDate}")
            Log.i(TAG, "生产厂家: ${llmResult.drugInfo.manufacturer}")
            Log.i(TAG, "批号/注册号: ${llmResult.drugInfo.batchNumber}")

            // 打印候选详情
            for ((fieldKey, fieldCandidates) in llmResult.allCandidates) {
                for (c in fieldCandidates.candidates) {
                    Log.i(TAG, "  [$fieldKey] ${c.value} (conf=${"%.2f".format(c.confidence)}) ${c.reason}")
                }
            }
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            if (llmResult.error.isNotEmpty()) {
                Log.e(TAG, "LLM Error: ${llmResult.error}")
            }
        } else {
            Log.w(TAG, "DeepSeek 未配置，跳过 LLM 提取")
        }

        bitmap.recycle()
    }

    @Test
    fun test2_date_field() {
        val bitmap = loadBitmap("image/date_field.jpg")!!
        val testName = "date_field.jpg"

        Log.i(TAG, "╔════════════════════════════════════════════════╗")
        Log.i(TAG, "║  $testName")
        Log.i(TAG, "╚════════════════════════════════════════════════╝")

        // ── Step 1: OCR ──
        val ocrResult = ocrEngine.recognizeStructured(bitmap)
        Log.i(TAG, "【OCR 原始输出】━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, ocrResult.fullText)
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // ── Step 2: LLM 结构化提取 ──
        val client = deepSeekClient
        if (client != null) {
            val llmResult = client.extractDrugInfo(
                rawText = ocrResult.fullText,
                ocrLines = ocrResult.lines,
                voiceInputDrugName = ""
            )
            Log.i(TAG, "【LLM 提取结果】━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.i(TAG, "药品名称: ${llmResult.drugInfo.drugName}")
            Log.i(TAG, "有效期至: ${llmResult.drugInfo.expiryDate}")
            Log.i(TAG, "生产厂家: ${llmResult.drugInfo.manufacturer}")
            Log.i(TAG, "批号/注册号: ${llmResult.drugInfo.batchNumber}")

            // 打印候选详情
            for ((fieldKey, fieldCandidates) in llmResult.allCandidates) {
                for (c in fieldCandidates.candidates) {
                    Log.i(TAG, "  [$fieldKey] ${c.value} (conf=${"%.2f".format(c.confidence)}) ${c.reason}")
                }
            }
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            if (llmResult.error.isNotEmpty()) {
                Log.e(TAG, "LLM Error: ${llmResult.error}")
            }
        } else {
            Log.w(TAG, "DeepSeek 未配置，跳过 LLM 提取")
        }

        bitmap.recycle()
    }

    // ==================== 辅助 ====================

    private fun loadBitmap(assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetPath: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "OCR_TEST"
    }
}
