package com.example.myocr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myocr.drugentry.DeepSeekClient
import org.junit.Test
import org.junit.runner.RunWith

/** 只渲染 prompt，不调 API。—— 查看全部字段和每个字段的 prompt 实际长什么样。 */
@RunWith(AndroidJUnit4::class)
class PromptPreviewTest {

    /** 用 full_fields.jpg 的 OCR 结果作为样本 */
    private val sampleOcrText = """欣音
国药准字H2004643
奥美拉唑肠溶胶囊
道光。密封，在子提处保存
南建控业有限公司
产企业石的股意的业有限公司"""

    @Test
    fun previewAllPrompts() {
        val tag = "PROMPT_PREVIEW"
        val sep = "─".repeat(60)

        // ── 全字段 ──
        Log.i(tag, sep)
        Log.i(tag, "【全字段提取】System Prompt")
        Log.i(tag, sep)
        Log.i(tag, DeepSeekClient.buildFullSystemPrompt())
        Log.i(tag, "")
        Log.i(tag, sep)
        Log.i(tag, "【全字段提取】User Message")
        Log.i(tag, sep)
        Log.i(tag, DeepSeekClient.formatSimpleUserMessage(sampleOcrText, ""))
        Log.i(tag, "\n\n")

        // ── 逐个字段 ──
        val fields =
                listOf(
                        "drugName" to "药品名称",
                        "expiryDate" to "有效期至至",
                        "manufacturer" to "生产厂家",
                        "approvalNumber" to "批准文号",
                        "lotNumber" to "生产批号"
                )
        for ((key, label) in fields) {
            Log.i(tag, sep)
            Log.i(tag, "【单字段 $label（$key）】System Prompt")
            Log.i(tag, sep)
            Log.i(tag, DeepSeekClient.buildSingleFieldSystemPrompt(key))
            Log.i(tag, "")
            Log.i(tag, sep)
            Log.i(tag, "【单字段 $label（$key）】User Message")
            Log.i(tag, sep)
            Log.i(tag, DeepSeekClient.formatSimpleUserMessage(sampleOcrText, "", label))
            Log.i(tag, "\n\n")
        }
    }
}
