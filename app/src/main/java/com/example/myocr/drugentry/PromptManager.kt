package com.example.myocr.drugentry

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.myocr.OcrLine
import com.example.myocr.R

/**
 * LLM 提示词模板管理器
 *
 * - [fullExtraction] — 从 full_extraction.md 加载完整提取 prompt
 * - [buildFieldPrompt] — 按字段动态构造专用提取 prompt（无需 per_field.md）
 */
class PromptManager(context: Context) {

    /** 带位置信息的完整提取 prompt */
    val fullExtraction: PromptConfig

    init {
        fullExtraction = loadFromRaw(context, R.raw.full_extraction)
        Log.d(TAG, "Prompt templates loaded: full=${fullExtraction.systemTemplate.length}chars")
    }

    // ==================== 构建用户消息 ====================

    /**
     * 构建带位置信息的用户消息（[fullExtraction] 模板）
     */
    fun buildPositionalUserMessage(ocrLines: List<OcrLine>, voiceInput: String): String {
        val imageHeight = ocrLines.maxOfOrNull { it.boundingBox?.bottom ?: 0 } ?: 0
        val lines = ocrLines.mapIndexed { i, line ->
            "行 ${i + 1} | 位置: ${line.estimateVerticalPosition(imageHeight)} | ${line.text}"
        }
        return fillUserTemplate(fullExtraction.userTemplate, lines.joinToString("\n"), voiceInput, "")
    }

    /**
     * 构建无位置信息的用户消息（[fullExtraction] 模板，仅行号）
     */
    fun buildSimpleUserMessage(rawText: String, voiceInput: String): String {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
            .mapIndexed { i, line -> "行 ${i + 1} | $line" }
        return fillUserTemplate(fullExtraction.userTemplate, lines.joinToString("\n"), voiceInput, "")
    }

    /**
     * 按字段提取：从 per_field.md 加载对应字段的 system prompt，组装完整的 API 请求。
     *
     * @param fieldKey  drugName / expiryDate / manufacturer / batchNumber
     * @param rawText   OCR 原始文本
     * @param voiceInput 用户语音输入
     */
    fun buildFieldPrompt(
        fieldKey: String,
        rawText: String,
        voiceInput: String
    ): FieldRequest = buildFieldPromptStatic(fieldKey, rawText, voiceInput)

    private fun fillUserTemplate(
        template: String, ocrLinesStr: String, voiceInput: String, fieldDisplayName: String
    ): String = fillUserTemplateStatic(template, ocrLinesStr, voiceInput, fieldDisplayName)

    // ==================== 配置 ====================

    data class PromptConfig(
        val systemTemplate: String,
        val userTemplate: String
    )

    data class FieldRequest(
        val systemPrompt: String,
        val userMessage: String
    )

    companion object {
        private const val TAG = "PromptManager"
        internal const val SEPARATOR = "\n---\n"

        internal fun loadFromRaw(context: Context, resId: Int): PromptConfig {
            val text = context.resources.openRawResource(resId)
                .bufferedReader().use { it.readText() }
            return parseMarkdownTemplate(text)
        }

        @VisibleForTesting
        internal fun parseMarkdownTemplate(text: String): PromptConfig {
            val idx = text.indexOf(SEPARATOR)
            return if (idx >= 0) {
                PromptConfig(
                    systemTemplate = text.substring(0, idx).trim(),
                    userTemplate = text.substring(idx + SEPARATOR.length).trim()
                )
            } else {
                PromptConfig(systemTemplate = text.trim(), userTemplate = "")
            }
        }

        @VisibleForTesting
        internal fun fillUserTemplateStatic(
            template: String, ocrLinesStr: String, voiceInput: String, fieldDisplayName: String
        ): String {
            val voiceSection = if (voiceInput.isNotBlank()) {
                "用户语音输入文本：\n$voiceInput"
            } else { "" }
            return template
                .replace("{{ocr_lines}}", ocrLinesStr)
                .replace("{{voice_section}}", voiceSection)
                .replace("{{field_display_name}}", fieldDisplayName)
        }

        @VisibleForTesting
        internal fun formatRawTextLines(rawText: String): String {
            return rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
                .mapIndexed { i, line -> "行 ${i + 1} | $line" }
                .joinToString("\n")
        }

        // ==================== 逐字段提取 prompt ====================

        /**
         * 根据字段名称动态构造专用提取 prompt。
         * 无需外部配置文件，代码内按字段类型生成不同的提取规则。
         */
        @VisibleForTesting
        internal fun buildFieldPromptStatic(
            fieldKey: String,
            rawText: String,
            voiceInput: String
        ): FieldRequest {
            val lines = formatRawTextLines(rawText)
            val voiceSection = if (voiceInput.isNotBlank()) {
                "\n用户语音输入文本：\n$voiceInput\n"
            } else { "" }

            val systemPrompt = defaultFieldPrompt(fieldKey)
            val label = fieldLabel(fieldKey)
            val userMessage = "OCR识别文本：\n${lines}${voiceSection}\n从以上信息，提取药品${label}。"
            return FieldRequest(systemPrompt, userMessage)
        }

        private fun fieldLabel(key: String): String = when (key) {
            "drugName" -> "名称"
            "expiryDate" -> "有效期"
            "manufacturer" -> "生产厂家"
            "batchNumber" -> "批号/注册号"
            else -> "信息"
        }

        private fun defaultFieldPrompt(key: String): String = when (key) {
            "drugName" -> """你是一个药品信息提取助手。从 OCR 识别文本中提取药品名称（drugName）。

药品名称必须是药品的真正名称，如「阿莫西林胶囊」「布洛芬缓释片」。

提取规则：
- OCR 识别可能产生错字、漏字、乱码，可根据上下文和药品常识修正 OCR 拼写错误
- 如果存在语音输入，可作为参考但不是唯一依据
- ❌ 不是药名的例子：「国药准字」「批准文号」
- ❌ OCR 文本中没有提到任何药名时，返回空 candidates，不得编造

输出格式：
```json
{"drugName": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，返回纯 JSON。""".trimIndent()

            "expiryDate" -> """你是一个药品信息提取助手。从 OCR 识别文本中提取有效期（expiryDate）。

有效期格式：yyyy-MM 或 yyyy-MM-dd（如 2026-09 或 2026-09-15）。

提取规则：
- 优先寻找标注「有效期至」「有效期」「EXP」「失效期」后面的日期
- OCR 可能将分隔符识别为点(.)、斜杠(/)、中文句号，统一转为横杠(-)
- 如果只有「生产日期」+「保质期 X 年/月」，可以计算：有效期 = 生产日期 + 保质期（前提是两个值均在 OCR 文本中真实存在）
- ❌ 不得自行编造日期。OCR 文本中无日期相关内容时，返回空 candidates

输出格式：
```json
{"expiryDate": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，返回纯 JSON。""".trimIndent()

            "manufacturer" -> """你是一个药品信息提取助手。从 OCR 识别文本中提取生产厂家（manufacturer）。

生产厂家通常包含「有限公司」「制药」「药业」「生物」等关键词。

提取规则：
- 优先寻找标注「生产企业」「生产单位」「厂家」「Manufacturer」后面的名称
- ❌ 只提取生产企业本身，不要提取经销商、代理商名称
- ❌ OCR 文本中没有出现任何厂家名称时，返回空 candidates，不得编造

输出格式：
```json
{"manufacturer": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，返回纯 JSON。""".trimIndent()

            "batchNumber" -> """你是一个药品信息提取助手。从 OCR 识别文本中提取批号/注册号（batchNumber）。

包含两种类型：
1. 批准文号：格式如「国药准字H20093069」，特征是「国药准字」「批准文号」「注册证号」开头
2. 生产批号：批次追溯编号，标注为「批号」「Lot」「Batch No.」

提取规则：
- 优先提取明确标注了「批准文号」或「批号」标签的值
- 如果两者都存在，分别作为不同候选返回，按置信度排序
- OCR 常将「准」识别为「度」，将「H」识别为「1」「I」
- ❌ OCR 文本中没有出现任何批号/注册号时，返回空 candidates，不得编造

输出格式：
```json
{"batchNumber": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，返回纯 JSON。""".trimIndent()

            else -> """你是一个药品信息提取助手。从 OCR 识别文本中提取指定字段信息。

字段：$key

⚠️ OCR 文本中没有出现的任何信息，不得自行编造或推断。找不到则返回空 candidates。

输出格式：
```json
{"$key": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
返回纯 JSON，不要包含其他文字。""".trimIndent()
        }
    }
}
