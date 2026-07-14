package com.example.myocr.drugentry

import android.content.Context
import android.util.Log
import com.example.myocr.OcrLine
import org.json.JSONObject

/**
 * LLM 提示词模板管理器
 *
 * 从 [prompts.json] 加载结构化模板，代码组装为完整的 system_prompt 和 user_message。
 * 模板中使用 {{xxx}} 作为运行时替换的占位符。
 *
 * 支持两套独立 prompt：
 * - [fullExtraction] — 带位置信息的完整提取（裁剪后 OCR，含行位置描述）
 * - [perField] — 无位置信息的简单提取（备用路径）
 */
class PromptManager(context: Context) {

    /** 带位置信息的完整提取 prompt */
    val fullExtraction: PromptConfig
    /** 按字段精简提取 prompt */
    val perField: PromptConfig

    init {
        val root = try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("prompts", "raw", context.packageName)
            )
            val text = inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prompts.json, using defaults", e)
            JSONObject()
        }

        fullExtraction = PromptConfig.fromJson(root.optJSONObject("full_extraction"))
        perField = PromptConfig.fromJson(root.optJSONObject("per_field"))

        Log.d(TAG, "Prompt templates loaded: full=${fullExtraction.systemPrompt.length}chars, " +
                "perField=${perField.systemPrompt.length}chars")
    }

    // ==================== 构建用户消息 ====================

    /**
     * 构建带位置信息的用户消息（[fullExtraction] 模板）
     */
    fun buildPositionalUserMessage(ocrLines: List<OcrLine>, voiceInput: String): String {
        val config = fullExtraction
        val imageHeight = ocrLines.maxOfOrNull { it.boundingBox?.bottom ?: 0 } ?: 0

        val lines = ocrLines.mapIndexed { i, line ->
            config.lineFormat
                .replace("{{line_number}}", "${i + 1}")
                .replace("{{position}}", line.estimateVerticalPosition(imageHeight))
                .replace("{{text}}", line.text)
        }
        return buildUserMessage(lines, voiceInput, config)
    }

    /**
     * 构建无位置信息的用户消息（[perField] 模板）
     */
    fun buildSimpleUserMessage(rawText: String, voiceInput: String): String {
        val config = perField
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
            .mapIndexed { i, line ->
                config.lineFormat
                    .replace("{{line_number}}", "${i + 1}")
                    .replace("{{text}}", line)
            }
        return buildUserMessage(lines, voiceInput, config)
    }

    private fun buildUserMessage(lines: List<String>, voiceInput: String, config: PromptConfig): String {
        val sb = StringBuilder()
        sb.append(config.ocrLabel).append("\n")
        sb.append(lines.joinToString("\n"))
        sb.append("\n\n")

        if (voiceInput.isNotBlank()) {
            val voiceSection = config.voiceTemplate
                .replace("{{voice_label}}", config.voiceLabel)
                .replace("{{voice_text}}", voiceInput)
            sb.append(voiceSection)
        }

        sb.append(config.footer)
        return sb.toString()
    }

    // ==================== 配置 ====================

    /**
     * 单个 prompt 的编译后配置
     */
    data class PromptConfig(
        val systemPrompt: String,
        val ocrLabel: String,
        val lineFormat: String,
        val voiceLabel: String,
        val voiceTemplate: String,
        val footer: String
    ) {
        companion object {
            // 默认值（JSON 加载失败时兜底）
            const val DEFAULT_SYSTEM = "你是一个药品信息提取助手。从 OCR 文本中提取药品信息。返回 JSON。"
            const val DEFAULT_OCR_LABEL = "OCR识别文本："
            const val DEFAULT_LINE = "行 {{line_number}} | {{text}}"
            const val DEFAULT_VOICE_LABEL = "用户语音输入文本："
            const val DEFAULT_VOICE_TEMPLATE = "{{voice_label}}\n{{voice_text}}\n\n"
            const val DEFAULT_FOOTER = "从以上信息，提取药品信息。"
        }
    }

    companion object {
        private const val TAG = "PromptManager"

        /** 从 JSON 对象构建 PromptConfig */
        fun PromptConfig.Companion.fromJson(json: JSONObject?): PromptConfig {
            if (json == null) {
                return PromptConfig(
                    systemPrompt = PromptConfig.DEFAULT_SYSTEM,
                    ocrLabel = PromptConfig.DEFAULT_OCR_LABEL,
                    lineFormat = PromptConfig.DEFAULT_LINE,
                    voiceLabel = PromptConfig.DEFAULT_VOICE_LABEL,
                    voiceTemplate = PromptConfig.DEFAULT_VOICE_TEMPLATE,
                    footer = PromptConfig.DEFAULT_FOOTER
                )
            }

            val sections = json.optJSONArray("system_sections")
            val systemPrompt = if (sections != null && sections.length() > 0) {
                (0 until sections.length()).joinToString("\n\n") { i ->
                    sections.optString(i, "")
                }
            } else {
                PromptConfig.DEFAULT_SYSTEM
            }

            val user = json.optJSONObject("user") ?: JSONObject()

            return PromptConfig(
                systemPrompt = systemPrompt,
                ocrLabel = user.optString("ocr_label", PromptConfig.DEFAULT_OCR_LABEL),
                lineFormat = user.optString("line_format", PromptConfig.DEFAULT_LINE),
                voiceLabel = user.optString("voice_label", PromptConfig.DEFAULT_VOICE_LABEL),
                voiceTemplate = user.optString("voice_template", PromptConfig.DEFAULT_VOICE_TEMPLATE),
                footer = user.optString("footer", PromptConfig.DEFAULT_FOOTER)
            )
        }
    }
}
