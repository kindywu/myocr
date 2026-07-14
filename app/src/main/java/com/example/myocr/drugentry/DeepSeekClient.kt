package com.example.myocr.drugentry

import android.util.Log
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
 *
 * == 输出 ==
 * - 每个字段返回多个候选结果（带置信度 + 原因）
 * - 经过正则验证后自动选择最佳候选
 */
class DeepSeekClient(private val apiKey: String) {

    companion object {
        private const val TAG = "DeepSeekClient"
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val MODEL = "deepseek-chat"
        private const val TIMEOUT_MS = 30_000

        /** 字段 key 列表，用于遍历 */
        private val FIELD_KEYS = listOf("drugName", "expiryDate", "manufacturer", "batchNumber")

        /**
         * 提取药品信息的系统提示词（增强版）
         *
         * 关键改进：
         * 1. 明确反例规则，防止"国药准字"被当作药名
         * 2. 多候选输出，每个字段返回 top-3
         * 3. 按行编号输入，保留分行/顺序上下文
         * 4. 要求给出置信度和判断原因
         */
        private const val SYSTEM_PROMPT = """你是一个药品信息提取助手。从药品包装 OCR 文本中提取结构化药品信息。

## 核心规则（严格遵守）

1. **药品名称（drugName）** 必须是药品的真正名称，如「阿莫西林胶囊」「布洛芬缓释片」。
   - ❌ 「国药准字H20093069」→ 这是批准文号，不是药名
   - ❌ 「请仔细阅读说明书」→ 这是警示语，不是药名
   - ❌ 「批准文号」「注册证号」「产品批号」→ 这些是字段标签，不是药名
   - ✅ 「阿莫西林胶囊」「复方丹参滴丸」「布洛芬缓释片」

2. **批号/注册号（batchNumber）** 包含：批准文号（国药准字/注册证号）、生产批号（Lot/Batch）。

3. **生产厂家（manufacturer）** 是药品生产企业名称，通常含「有限公司」「制药」「药业」等关键词。

4. **有效期（expiryDate）** 格式 yyyy-MM 或 yyyy-MM-dd（如 2026-09 或 2026-09-15），根据识别的精度决定。

## 输入格式

OCR 文本已按行编号（行 1、行 2...），行号反映在包装上的阅读顺序。
利用行号和前后文关系来判断语义。

## 输出格式

对每个字段返回候选列表，每个候选包含 value、confidence（0-1）、reason。
如果某个字段无法确定任何候选项，返回空 candidates 列表。

返回纯 JSON，不要包含其他文字。"""

        /**
         * 构建带行号的输入文本
         */
        private fun buildFormattedInput(rawText: String): String {
            val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
            val numbered = lines.mapIndexed { i, line ->
                "行 ${i + 1} | $line"
            }
            val totalLines = numbered.size
            return "以下是从药品包装上 OCR 识别出的文本，共 $totalLines 行，按阅读顺序排列：\n\n" +
                    numbered.joinToString("\n")
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
         *
         * 利用 OcrLine 的 boundingBox 估算各行在图片上的位置，
         * 帮助 LLM 判断"上方大字是批准文号、中部是药名"等语义。
         */
        private fun buildPositionalInput(lines: List<OcrLine>): String {
            // 估算图片高度（取最大 bottom）
            val imageHeight = lines.maxOfOrNull {
                it.boundingBox?.bottom ?: 0
            } ?: 0

            val numbered = lines.mapIndexed { i, line ->
                val position = line.estimateVerticalPosition(imageHeight)
                "行 ${i + 1} | 位置: $position | ${line.text}"
            }
            val totalLines = numbered.size
            return "以下是从药品包装上 OCR 识别出的文本，按阅读顺序排列，每行附有在包装上的垂直位置：\n\n" +
                    numbered.joinToString("\n")
        }

        /** 用于验证药名字段的反向规则 */
        private val DRUG_NAME_BLACKLIST = listOf(
            "国药准字", "批准文号", "注册证号", "产品批号",
            "请仔细阅读", "说明书", "不良反应"
        )
    }

    /**
     * 单个候选结果
     */
    data class Candidate(
        val value: String,
        val confidence: Float,
        val reason: String = ""
    )

    /**
     * 字段候选集合
     */
    data class FieldCandidates(
        val candidates: List<Candidate> = emptyList()
    ) {
        /** 获取置信度最高的候选值（空则返回空串） */
        val bestValue: String get() = candidates
            .maxByOrNull { it.confidence }
            ?.value ?: ""
    }

    /**
     * API 调用结果
     */
    data class Result(
        val success: Boolean,
        /** 字段 → 候选列表 */
        val allCandidates: Map<String, FieldCandidates> = emptyMap(),
        /** 每个字段的最佳候选（经过正则验证后） */
        val drugInfo: DrugInfo = DrugInfo(),
        val error: String = ""
    )

    /**
     * 调用 DeepSeek API 提取药品信息（带 OCR 行位置信息）
     *
     * @param rawText  ML Kit OCR 原始识别文本
     * @param ocrLines OCR 逐行识别结果（含位置信息），用于构建更精确的输入
     * @return 解析结果（含多候选 + 最佳值）
     */
    fun extractDrugInfo(rawText: String, ocrLines: List<OcrLine>): Result {
        if (rawText.isBlank()) {
            return Result(false, error = "OCR 文本为空")
        }

        return try {
            // 1️⃣ 预过滤（剔除明显噪声行）
            val filteredLines = filterLinesByText(ocrLines)
            val filteredText = filteredLines.joinToString("\n") { it.text }

            // 2️⃣ 格式化（带行号 + 位置描述）
            val formattedText = buildPositionalInput(filteredLines)

            // 3️⃣ 调用 API
            val responseJson = callApi(formattedText)

            // 4️⃣ 解析多候选响应
            val rawCandidates = parseResponse(responseJson)

            // 5️⃣ 正则后验证 + 择优
            val (validated, bestInfo) = validateAndSelect(rawCandidates)

            Log.d(TAG, "LLM candidates (positional): ${validated.keys}")
            Log.d(TAG, "Best: drugName=[${bestInfo.drugName}] expiry=[${bestInfo.expiryDate}] " +
                    "mfg=[${bestInfo.manufacturer}] batch=[${bestInfo.batchNumber}]")

            Result(
                success = true,
                allCandidates = validated,
                drugInfo = bestInfo
            )
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek API call failed", e)
            Result(false, error = e.message ?: "未知错误")
        }
    }

    /**
     * 调用 DeepSeek API 提取药品信息
     *
     * @param rawText ML Kit OCR 原始识别文本
     * @return 解析结果（含多候选 + 最佳值）
     */
    fun extractDrugInfo(rawText: String): Result {
        if (rawText.isBlank()) {
            return Result(false, error = "OCR 文本为空")
        }

        return try {
            // 1️⃣ 预过滤（剔除明显噪声行）
            val filteredText = DrugOcrParser.preFilter(rawText)

            // 2️⃣ 格式化（带行号）
            val formattedText = buildFormattedInput(filteredText)

            // 3️⃣ 调用 API
            val responseJson = callApi(formattedText)

            // 4️⃣ 解析多候选响应
            val rawCandidates = parseResponse(responseJson)

            // 5️⃣ 正则后验证 + 择优
            val (validated, bestInfo) = validateAndSelect(rawCandidates)

            Log.d(TAG, "LLM candidates parsed for fields: ${validated.keys}")
            Log.d(TAG, "Best: drugName=[${bestInfo.drugName}] expiry=[${bestInfo.expiryDate}] " +
                    "mfg=[${bestInfo.manufacturer}] batch=[${bestInfo.batchNumber}]")

            Result(
                success = true,
                allCandidates = validated,
                drugInfo = bestInfo
            )
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek API call failed", e)
            Result(false, error = e.message ?: "未知错误")
        }
    }

    private fun callApi(formattedText: String): String {
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("response_format", JSONObject().put("type", "json_object"))

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", formattedText)
            })
            put("messages", messages)
            put("temperature", 0.1)
        }

        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
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
     *   "expiryDate": { "candidates": [...] },
     *   "manufacturer": { "candidates": [...] },
     *   "batchNumber": { "candidates": [...] }
     * }
     *
     * 也兼容旧格式（直接字段值）：{ "drugName": "阿莫西林胶囊", ... }
     */
    private fun parseResponse(responseJson: String): Map<String, FieldCandidates> {
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

        for (fieldKey in FIELD_KEYS) {
            if (!resultJson.has(fieldKey)) {
                fieldMap[fieldKey] = FieldCandidates()
                continue
            }

            val fieldValue = resultJson.get(fieldKey)

            when (fieldValue) {
                is JSONArray -> {
                    // 格式 1: "drugName": [{"value": "...", "confidence": 0.95}, ...]
                    fieldMap[fieldKey] = parseJsonArrayCandidates(fieldValue)
                }
                is JSONObject -> {
                    // 格式 2: "drugName": {"candidates": [...]}
                    if (fieldValue.has("candidates")) {
                        fieldMap[fieldKey] = parseJsonArrayCandidates(fieldValue.getJSONArray("candidates"))
                    } else {
                        // 格式 3: {} — 无候选
                        fieldMap[fieldKey] = FieldCandidates()
                    }
                }
                is String -> {
                    // 格式 4（向后兼容）: "drugName": "阿莫西林胶囊"
                    val str = fieldValue.trim()
                    fieldMap[fieldKey] = if (str.isNotEmpty()) {
                        FieldCandidates(listOf(Candidate(str, 0.5f, "旧格式兼容")))
                    } else {
                        FieldCandidates()
                    }
                }
                else -> {
                    fieldMap[fieldKey] = FieldCandidates()
                }
            }
        }

        return fieldMap
    }

    /**
     * 解析 JSON 数组格式的候选列表
     */
    private fun parseJsonArrayCandidates(arr: JSONArray): FieldCandidates {
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
     * 正则后验证 + 择优
     *
     * 对每个字段：
     * 1. 遍历 LLM 候选，剔除未通过正则验证的候选项
     * 2. 从剩余候选中选置信度最高的
     * 3. 如果全部被剔除，该字段为空
     *
     * @return Pair(验证后的候选, 最佳值的 DrugInfo)
     */
    private fun validateAndSelect(raw: Map<String, FieldCandidates>): Pair<Map<String, FieldCandidates>, DrugInfo> {
        val validatedMap = mutableMapOf<String, FieldCandidates>()
        val bestValues = mutableMapOf<String, String>()

        for (fieldKey in FIELD_KEYS) {
            val fieldCandidates = raw[fieldKey] ?: FieldCandidates()

            // 对每个候选执行正则验证
            val validCandidates = fieldCandidates.candidates.filter { candidate ->
                when (fieldKey) {
                    "drugName" -> DrugOcrParser.isValidDrugName(candidate.value)
                    "expiryDate" -> DrugOcrParser.isValidExpiryDate(candidate.value)
                    "manufacturer" -> DrugOcrParser.isValidManufacturer(candidate.value)
                    "batchNumber" -> DrugOcrParser.isValidBatchNumber(candidate.value)
                    else -> true
                }
            }

            validatedMap[fieldKey] = FieldCandidates(validCandidates)

            // 额外安全检查：对 drugName 做黑名单检查
            val best = if (fieldKey == "drugName") {
                validCandidates
                    .filter { c -> DRUG_NAME_BLACKLIST.none { c.value.contains(it) } }
                    .maxByOrNull { it.confidence }
            } else {
                validCandidates.maxByOrNull { it.confidence }
            }

            bestValues[fieldKey] = best?.value ?: ""
        }

        val drugInfo = DrugInfo(
            drugName = bestValues["drugName"] ?: "",
            expiryDate = bestValues["expiryDate"] ?: "",
            manufacturer = bestValues["manufacturer"] ?: "",
            batchNumber = bestValues["batchNumber"] ?: ""
        )

        return Pair(validatedMap, drugInfo)
    }
}
