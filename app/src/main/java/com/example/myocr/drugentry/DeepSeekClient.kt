package com.example.myocr.drugentry

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.myocr.OcrLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek API 客户端
 *
 * 调用 DeepSeek 模型将原始 OCR 文本解析为结构化药品信息。
 * 使用 HttpURLConnection 保持零外部依赖。
 *
 * == 输入 ==
 * - 按行编号的 OCR 文本（保留原始分行和阅读顺序）
 * - 可选的用户语音输入药品名称（辅助 LLM 判断）
 *
 * == 输出 ==
 * - 每个字段返回多个候选结果（带置信度 + 原因）
 * - 直接取置信度最高的候选（无正则后校验）
 */
class DeepSeekClient(
    private val apiKey: String,
    private val promptManager: PromptManager,
    private val config: LlmConfig = LlmConfig()
) {

    /** LLM 请求超时（毫秒） */
    private val timeoutMs: Int get() = config.timeoutMs

    /**
     * LLM 配置数据类
     */
    data class LlmConfig(
        val apiUrl: String = "https://api.deepseek.com/chat/completions",
        val model: String = "deepseek-v4-flash",
        val timeoutMs: Int = 30_000
    )

    companion object {
        private const val TAG = "DeepSeekClient"

        /** 字段 key 列表，用于遍历 */
        @VisibleForTesting
        internal val FIELD_KEYS = listOf("drugName", "expiryDate", "manufacturer", "batchNumber")

        // ==================== 响应解析（纯函数，可单独测试） ====================

        /**
         * 解析 LLM 响应中的多候选 JSON
         *
         * 期望格式：
         * {
         *   "drugName": {
         *     "candidates": [
         *       {"value": "阿莫西林胶囊", "confidence": 0.95, "reason": "..."},
         *       {"value": "阿莫西林", "confidence": 0.30, "reason": "..."}
         *     ]
         *   },
         *   ...
         * }
         */
        @VisibleForTesting
        internal fun parseResponse(responseJson: String): Map<String, FieldCandidates> {
            val json = JSONObject(responseJson)
            val choices = json.getJSONArray("choices")

            if (choices.length() == 0) {
                throw RuntimeException("API 返回空结果")
            }

            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val resultJson = JSONObject(content)
            val fieldMap = mutableMapOf<String, FieldCandidates>()

            // 1️⃣ 解析标准字段
            for (fieldKey in FIELD_KEYS) {
                fieldMap[fieldKey] = extractFieldCandidates(resultJson, fieldKey)
            }

            // 2️⃣ 兼容处理：markdown prompt 将 batchNumber 拆分为 approvalNumber + lotNumber
            val existingBatch = fieldMap["batchNumber"]?.candidates ?: emptyList()
            if (existingBatch.isEmpty() && (resultJson.has("approvalNumber") || resultJson.has("lotNumber"))) {
                val extra = mutableListOf<Candidate>()
                extra.addAll(extractFieldCandidates(resultJson, "approvalNumber").candidates)
                extra.addAll(extractFieldCandidates(resultJson, "lotNumber").candidates)
                if (extra.isNotEmpty()) {
                    fieldMap["batchNumber"] = FieldCandidates(extra.sortedByDescending { it.confidence })
                }
            }

            return fieldMap
        }

        /**
         * 从 JSON 中提取指定字段的候选列表
         */
        @VisibleForTesting
        internal fun extractFieldCandidates(json: JSONObject, fieldKey: String): FieldCandidates {
            if (!json.has(fieldKey)) return FieldCandidates()

            val fieldValue = json.get(fieldKey)

            return when (fieldValue) {
                is JSONArray -> {
                    parseJsonArrayCandidates(fieldValue)
                }
                is JSONObject -> {
                    if (fieldValue.has("candidates")) {
                        parseJsonArrayCandidates(fieldValue.getJSONArray("candidates"))
                    } else {
                        FieldCandidates()
                    }
                }
                is String -> {
                    val str = fieldValue.trim()
                    if (str.isNotEmpty()) {
                        FieldCandidates(listOf(Candidate(str, 0.5f, "旧格式兼容")))
                    } else {
                        FieldCandidates()
                    }
                }
                else -> FieldCandidates()
            }
        }

        /**
         * 解析 JSONArray 候选列表
         */
        @VisibleForTesting
        internal fun parseJsonArrayCandidates(arr: JSONArray): FieldCandidates {
            val candidates = mutableListOf<Candidate>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val value = item.optString("value", "").trim()
                if (value.isBlank()) continue

                val confidence = item.optDouble("confidence", 0.0).toFloat()
                val reason = item.optString("reason", "")
                candidates.add(Candidate(value, confidence, reason))
            }
            return FieldCandidates(candidates)
        }

        /**
         * 用 [DrugOcrParser.preFilter] 过滤 OcrLine 列表
         */
        private fun filterLinesByText(lines: List<OcrLine>): List<OcrLine> {
            return lines.filter { line ->
                val text = line.text
                text.length in 1..100 &&
                    !text.matches(Regex("""^\d+${'$'}""")) &&
                    text.any { it in '一'..'鿿' }
            }
        }

        /**
         * 构建带行号 + 垂直位置的输入文本
         */
        private fun buildPositionalInput(lines: List<OcrLine>, voiceInput: String): String {
            val imageHeight = lines.maxOfOrNull {
                it.boundingBox?.bottom ?: 0
            } ?: 0

            val numbered = lines.mapIndexed { i, line ->
                val position = line.estimateVerticalPosition(imageHeight)
                "行 ${i + 1} | 位置: $position | ${line.text}"
            }

            val sb = StringBuilder()
            sb.append("OCR识别文本：\n")
            sb.append(numbered.joinToString("\n"))
            sb.append("\n\n")
            if (voiceInput.isNotBlank()) {
                sb.append("用户语音输入文本：\n")
                sb.append(voiceInput)
                sb.append("\n\n")
            }
            sb.append("从以上两段信息，提取药品信息。")
            return sb.toString()
        }
    }

    // ==================== 数据结构 ====================

    data class Candidate(
        val value: String,
        val confidence: Float,
        val reason: String = ""
    )

    data class FieldCandidates(
        val candidates: List<Candidate> = emptyList()
    ) {
        /** 获取置信度最高的候选值（空则返回空串） */
        val bestValue: String get() = candidates
            .maxByOrNull { it.confidence }
            ?.value ?: ""
    }

    data class Result(
        val success: Boolean,
        val allCandidates: Map<String, FieldCandidates> = emptyMap(),
        val drugInfo: DrugInfo = DrugInfo(),
        /** 实际发送给 LLM 的完整输入文本（调试用） */
        val formattedInput: String = "",
        /** LLM 原始响应 JSON（调试用，可直接用于 [parseResponse] 测试） */
        val rawApiResponse: String = "",
        val error: String = ""
    )

    // ==================== 主入口 ====================

    /**
     * 调用 DeepSeek API 提取药品信息（带 OCR 行位置信息 + 可选的语音输入）
     *
     * @param rawText  ML Kit OCR 原始识别文本
     * @param ocrLines OCR 逐行识别结果（含位置信息）
     * @param voiceInputDrugName 用户语音输入的药品名称（辅助 LLM 判断）
     * @return 解析结果
     */
    fun extractDrugInfo(
        rawText: String,
        ocrLines: List<OcrLine>,
        voiceInputDrugName: String = ""
    ): Result {
        if (rawText.isBlank()) {
            return Result(false, error = "OCR 文本为空")
        }

        return try {
            // 1️⃣ 预过滤
            val filteredLines = filterLinesByText(ocrLines)
            val filteredText = filteredLines.joinToString("\n") { it.text }

            // 2️⃣ 格式化（带行号 + 位置描述 + 可选的语音输入）
            val formattedText = promptManager.buildPositionalUserMessage(filteredLines, voiceInputDrugName)

            // 3️⃣ 调用 API（带位置 prompt）
            val responseJson = callApi(formattedText, promptManager.fullExtraction.systemTemplate)

            // 4️⃣ 解析多候选响应
            val rawCandidates = parseResponse(responseJson)

            // 5️⃣ 直接取最佳值
            val bestInfo = DrugInfo(
                drugName = rawCandidates["drugName"]?.bestValue ?: "",
                expiryDate = rawCandidates["expiryDate"]?.bestValue ?: "",
                manufacturer = rawCandidates["manufacturer"]?.bestValue ?: "",
                batchNumber = rawCandidates["batchNumber"]?.bestValue ?: ""
            )

            Log.d(TAG, "LLM result (positional): drugName=[${bestInfo.drugName}] " +
                    "expiry=[${bestInfo.expiryDate}] mfg=[${bestInfo.manufacturer}] batch=[${bestInfo.batchNumber}]")

            Result(
                success = true,
                allCandidates = rawCandidates,
                drugInfo = bestInfo,
                formattedInput = formattedText,
                rawApiResponse = responseJson
            )
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek API call failed", e)
            Result(false, error = e.message ?: "未知错误")
        }
    }

    /**
     * 调用 DeepSeek API 提取药品信息（无位置信息，简化版）
     *
     * @param rawText ML Kit OCR 原始识别文本
     * @param voiceInputDrugName 用户语音输入的药品名称（辅助 LLM 判断）
     * @return 解析结果
     */
    fun extractDrugInfo(
        rawText: String,
        voiceInputDrugName: String = ""
    ): Result {
        if (rawText.isBlank()) {
            return Result(false, error = "OCR 文本为空")
        }

        return try {
            // 1️⃣ 预过滤
            val filteredText = DrugOcrParser.preFilter(rawText)

            // 2️⃣ 格式化（带行号 + 可选的语音输入）
            val formattedText = promptManager.buildSimpleUserMessage(filteredText, voiceInputDrugName)

            // 3️⃣ 调用 API（无位置 prompt）
            val responseJson = callApi(formattedText, promptManager.fullExtraction.systemTemplate)

            // 4️⃣ 解析多候选响应
            val rawCandidates = parseResponse(responseJson)

            // 5️⃣ 直接取最佳值
            val bestInfo = DrugInfo(
                drugName = rawCandidates["drugName"]?.bestValue ?: "",
                expiryDate = rawCandidates["expiryDate"]?.bestValue ?: "",
                manufacturer = rawCandidates["manufacturer"]?.bestValue ?: "",
                batchNumber = rawCandidates["batchNumber"]?.bestValue ?: ""
            )

            Log.d(TAG, "LLM result: drugName=[${bestInfo.drugName}] " +
                    "expiry=[${bestInfo.expiryDate}] mfg=[${bestInfo.manufacturer}] batch=[${bestInfo.batchNumber}]")

            Result(
                success = true,
                allCandidates = rawCandidates,
                drugInfo = bestInfo,
                formattedInput = formattedText,
                rawApiResponse = responseJson
            )
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek API call failed", e)
            Result(false, error = e.message ?: "未知错误")
        }
    }

    // ==================== 内部方法 ====================

    private fun callApi(formattedText: String, systemPrompt: String): String {
        val payload = JSONObject().apply {
            put("model", config.model)
            put("response_format", JSONObject().put("type", "json_object"))

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", formattedText)
            })
            put("messages", messages)
            put("temperature", 0.1)
        }

        val url = URL(config.apiUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        Log.d(TAG, "API response code: $responseCode")

        val reader = BufferedReader(
            InputStreamReader(
                if (responseCode in 200..299) conn.inputStream else conn.errorStream
            )
        )
        val response = reader.readText()
        reader.close()
        conn.disconnect()

        if (responseCode !in 200..299) {
            throw RuntimeException("API error $responseCode: ${response.take(200)}")
        }

        return response
    }
}
