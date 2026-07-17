package com.example.myocr.drugentry

import android.content.Context
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

    /**
     * LLM 输出字段定义
     */
    data class FieldDef(
        /** 字段 key（对应 JSON 中的键名） */
        val key: String,
        /** 中文标签 */
        val labelCn: String,
        /** 英文标签 */
        val labelEn: String,
        /** 中文说明 */
        val descCn: String,
        /** 英文说明 */
        val descEn: String
    )

    companion object {
        private const val TAG = "DeepSeekClient"

        /** 字段 key 列表，用于遍历 */
        @VisibleForTesting
        internal val FIELD_KEYS = listOf("drugName", "expiryDate", "manufacturer", "batchNumber")

        /**
         * LLM 输出字段定义（含中英文描述）
         *
         * 对应 full_extraction.md 中定义的 JSON 输出结构。
         * batchNumber 由 approvalNumber + lotNumber 合并而来。
         */
        val FIELD_DEFINITIONS: Map<String, FieldDef> = mapOf(
            "drugName" to FieldDef(
                key = "drugName",
                labelCn = "药品名称",
                labelEn = "Drug Name",
                descCn = "药品的真正通用名称，如「阿莫西林胶囊」「布洛芬缓释片」。可根据药品知识修正 OCR 拼写错误，但不得凭空编造。",
                descEn = "Actual generic drug name, e.g. Amoxicillin Capsules. OCR errors may be corrected using drug knowledge, but must not fabricate."
            ),
            "expiryDate" to FieldDef(
                key = "expiryDate",
                labelCn = "有效期至",
                labelEn = "Expiry Date",
                descCn = "有效期至的日期，格式 yyyy-MM 或 yyyy-MM-dd，可由生产日期+保质期推算",
                descEn = "Expiration date in yyyy-MM or yyyy-MM-dd format; may be calculated from production date + shelf life"
            ),
            "manufacturer" to FieldDef(
                key = "manufacturer",
                labelCn = "生产厂家",
                labelEn = "Manufacturer",
                descCn = "药品实际生产企业名称，通常含「有限公司」「制药」「药业」等关键词",
                descEn = "Actual pharmaceutical manufacturer, commonly containing Co., Ltd., Pharma, etc."
            ),
            "approvalNumber" to FieldDef(
                key = "approvalNumber",
                labelCn = "批准文号",
                labelEn = "Approval Number",
                descCn = "药品监管审批编码，如「国药准字H20093069」，同一药品所有批次共用，固定不变",
                descEn = "Drug regulatory approval code, e.g. '国药准字H20093069'; constant across batches"
            ),
            "lotNumber" to FieldDef(
                key = "lotNumber",
                labelCn = "生产批号",
                labelEn = "Lot / Batch Number",
                descCn = "生产企业标注的批次追溯编号，标注为「批号」「Lot」「Batch No.」，每批不同",
                descEn = "Batch traceability number marked as '批号', 'Lot', or 'Batch No.'; varies per batch"
            )
        )

        /** LLM 输出的全部字段 key（含 [FIELD_KEYS] 未覆盖的） */
        val LLM_FIELD_KEYS: Set<String> get() = FIELD_DEFINITIONS.keys

        // ==================== 工厂方法 ====================

        /**
         * 创建 DeepSeekClient 实例，自动解析 API Key
         *
         * Key 优先级（高 → 低）：
         * 1. BuildConfig.DEEPSEEK_API_KEY（构建时从 local.properties 注入）
         * 2. assets/llm_config.properties 中的 deepseek_api_key
         *
         * 所有来源均为 `<test>` 占位符或空值时返回 null。
         *
         * @param context Android Context
         * @return DeepSeekClient 实例，或 null（未配置有效 Key）
         */
        fun create(context: Context): DeepSeekClient? {
            val apiKey = resolveApiKey(context) ?: return null
            return DeepSeekClient(apiKey = apiKey)
        }

        /**
         * 解析 API Key
         */
        private fun resolveApiKey(context: Context): String? {
            // 1. BuildConfig（构建时从 local.properties 注入）
            try {
                val buildKey = com.example.myocr.BuildConfig.DEEPSEEK_API_KEY
                if (buildKey.isNotBlank() && buildKey != "<test>") {
                    Log.d(TAG, "API Key resolved from BuildConfig")
                    return buildKey
                }
            } catch (_: Exception) {
                // BuildConfig 未生成，继续尝试 assets
            }

            // 2. assets/llm_config.properties
            try {
                val props = java.util.Properties()
                context.assets.open("llm_config.properties").use { props.load(it) }
                val key = props.getProperty("deepseek_api_key", "").trim()
                if (key.isNotBlank() && key != "<test>") {
                    Log.d(TAG, "API Key resolved from assets/llm_config.properties")
                    return key
                }
            } catch (_: Exception) {
                Log.w(TAG, "Failed to read llm_config.properties from assets")
            }

            Log.w(TAG, "No valid DeepSeek API Key configured. " +
                    "Add 'deepseekApiKey=sk-xxx' to local.properties or set in app settings.")
            return null
        }

        // ==================== 动态 Prompt 构建（基于 FIELD_DEFINITIONS） ====================

        /** 通用提取规则（全字段和单字段共用） */
        private val COMMON_RULES: String = """
## 通用规则
- confidence 为 0 到 1 之间的浮点数，数值越高越有把握
- 每个字段最多返回 3 个候选，按 confidence 从高到低排序
- 所有字段值必须使用包装原文语言如实提取，禁止翻译或改写
- reason 字段说明提取依据或置信度理由

## 反幻觉规则（必须严格执行）
- ⚠️ 所有字段值必须直接来源于 OCR 识别文本，不得自行编造
- ⚠️ 如果 OCR 文本中找不到某个字段信息，该字段必须返回空 candidates
- ⚠️ 禁止从其他字段的值中推算出新字段的信息
- ⚠️ 当 OCR 文本明显与药品包装无关时，所有字段均应返回空 candidates
""".trimIndent()

        /**
         * 从 [FIELD_DEFINITIONS] 动态构建全字段 system prompt
         *
         * 全字段返回 Map（5个字段各自的候选列表）
         */
        fun buildFullSystemPrompt(hasVoiceInput: Boolean = false): String = buildString {
            appendLine("你是一个药品信息提取助手。根据 OCR 识别文本提取结构化药品信息。")
            appendLine()
            appendLine("## 字段定义")
            appendLine()
            for (def in FIELD_DEFINITIONS.values) {
                appendLine("### ${def.labelCn}（${def.key}）")
                appendLine(def.descCn)
                appendLine()
            }
            appendLine(COMMON_RULES)
            if (hasVoiceInput) {
                appendLine()
                appendLine("## 语音输入处理规则")
                appendLine()
                appendLine("当用户提供了「语音输入文本」时，它是对药品包装实物的补充信息：")
                appendLine("- 语音输入是用户对着药品包装说话识别的结果，是用户确认的真实信息")
                appendLine("- 语音输入中的内容必须被采纳为对应字段的值，除非 OCR 文本中存在明显更准确的信息")
                appendLine("- 当语音输入内容与 OCR 文本中某个字段疑似冲突时，优先采信语音输入")
                appendLine("- 语音输入可以帮助确定 OCR 文本中的某段文字对应哪个字段")
                appendLine("- 如果语音输入提供了 OCR 文本中没有的信息，应将其填入最合理的字段")
            }
            appendLine()
            appendLine("## 字段特殊规则")
            appendLine()
            appendLine("### 药品名称（drugName）—— 允许知识修正")
            appendLine("药品名称是公开信息，LLM 可利用对药品的了解修正 OCR 拼写错误。")
            appendLine("例如 OCR 识别「奥美拉唑溶肢真」「奥美拉唑溶肠胶嚷」等，应结合上下文输出正确的「奥美拉唑溶肠胶囊」。")
            appendLine("如果 OCR 文本中完全没有与药品名称相关的线索，仍应返回空 candidates，不得凭空编造。")
            appendLine("confidence 应根据修正程度合理赋值：完全匹配 0.9+，轻度修正 0.7-0.9，较大修正 0.4-0.7。")
            appendLine()
            appendLine("### 批准文号（approvalNumber）—— 允许知识修正")
            appendLine("批准文号是公开的监管编码，LLM 可利用批准文号的编码规则修正 OCR 拼写错误。")
            appendLine("例如 OCR 识别「国药度字H20046431 0」应纠正为「国药准字H20046430」。")
            appendLine("如果 OCR 文本中完全没有与批准文号相关的线索，仍应返回空 candidates，不得凭空编造。")
            appendLine()
            appendLine("### 其他字段（有效期/生产厂家/生产批号）")
            appendLine("严格遵循上方反幻觉规则：所有值必须直接来源于 OCR 文本，不得自行编造或根据常识推断。")
            appendLine()
            appendLine("## 输出格式")
            appendLine("严格按照以下 JSON 结构返回（每个字段为一个 key-value）：")
            appendLine("{")
            val keys = FIELD_DEFINITIONS.keys.toList()
            for ((i, key) in keys.withIndex()) {
                append("  \"$key\": {\"candidates\": [{\"value\": \"...\", \"confidence\": 0.0, \"reason\": \"...\"}]}")
                if (i < keys.size - 1) appendLine(",") else appendLine()
            }
            appendLine("}")
            appendLine("无法确定的字段返回空 candidates：{\"candidates\": []}")
            appendLine("返回纯 JSON，不要包含其他文字。")
        }

        /**
         * 单字段 system prompt —— 每个字段完全独立自包含，不共享任何规则段
         */
        fun buildSingleFieldSystemPrompt(fieldKey: String): String {
            return when (fieldKey) {
                "drugName" -> buildDrugNamePrompt()
                "expiryDate" -> buildExpiryDatePrompt()
                "manufacturer" -> buildManufacturerPrompt()
                "approvalNumber" -> buildApprovalNumberPrompt()
                "lotNumber" -> buildLotNumberPrompt()
                else -> throw IllegalArgumentException("未知字段 key: $fieldKey")
            }
        }

        private fun buildDrugNamePrompt(): String = """
你是一个药品信息提取助手。从 OCR 识别文本中提取药品名称。

药品名称必须是药品的真正通用名称，如「阿莫西林胶囊」「布洛芬缓释片」。

规则：
- OCR 识别可能产生错字、漏字、乱码，可根据药品知识修正 OCR 拼写错误
- 例如 OCR 识别「奥美拉唑溶肢真」「奥美拉唑溶肠胶嚷」等，应结合上下文输出正确的「奥美拉唑肠溶胶囊」
- 如果存在语音输入，这是用户对着包装说话识别的结果，应优先采纳
- ❌ OCR 文本中没有提到任何药名时，返回空 candidates，不得编造
- 每条候选带 confidence（0~1）：完全匹配 0.9+，轻度修正 0.7-0.9，较大修正 0.4-0.7

输出格式：
```json
{"drugName": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，只返回 JSON。
""".trimIndent()

        private fun buildExpiryDatePrompt(): String = """
你是一个药品信息提取助手。从 OCR 识别文本中提取有效期至。

有效期格式：yyyy-MM 或 yyyy-MM-dd（如 2026-09 或 2026-09-15）。

规则：
- 优先寻找标注「有效期至」「有效期」「EXP」「失效期」后面的日期
- OCR 可能将分隔符识别为点(.)、斜杠(/)、中文句号，统一转为横杠(-)
- 如果只有「生产日期」+「保质期 X 年/月」，可以计算：有效期 = 生产日期 + 保质期
  （前提是两个值均在 OCR 文本中真实存在，并在 reason 中注明）
- ❌ 不得自行编造日期。OCR 文本中无日期相关内容时返回空 candidates
- ❌ 禁止根据药品名称或其他字段推断有效期

输出格式：
```json
{"expiryDate": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，只返回 JSON。
""".trimIndent()

        private fun buildManufacturerPrompt(): String = """
你是一个药品信息提取助手。从 OCR 识别文本中提取生产厂家。

生产厂家通常包含「有限公司」「制药」「药业」「生物」等关键词。

规则：
- 优先寻找标注「生产企业」「生产单位」「厂家」「Manufacturer」后面的名称
- ❌ 只提取 OCR 文本中真实出现的厂家名称，不得根据药品名称推断厂家
- ❌ 不要提取经销商、代理商名称
- ❌ OCR 文本中没有出现任何厂家名称时返回空 candidates

输出格式：
```json
{"manufacturer": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，只返回 JSON。
""".trimIndent()

        private fun buildApprovalNumberPrompt(): String = """
你是一个药品信息提取助手。从 OCR 识别文本中提取批准文号。

批准文号是药品监管审批编码，如「国药准字H20093069」「进口药品注册证号 H20180001」。
同一药品所有批次共用，固定不变。

规则：
- 优先提取明确标注了「批准文号」「国药准字」「注册证号」后面的编码
- OCR 常将「准」识别为「度」，将「H」识别为「1」「I」，数字之间也常混淆，可根据药品知识修正 OCR 拼写错误
- 例如 OCR 识别「国药度字H20046431 0」或「国药准字H2oo4643」等，应结合药品知识输出正确的「国药准字H20046430」
- ❌ OCR 文本中没有出现任何批准文号时，返回空 candidates，不得凭空编造

输出格式：
```json
{"approvalNumber": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，只返回 JSON。
""".trimIndent()

        private fun buildLotNumberPrompt(): String = """
你是一个药品信息提取助手。从 OCR 识别文本中提取生产批号。

生产批号是生产企业标注的批次追溯编号，通常标注为「批号」「Lot」「Batch No.」。
每个生产批次不同，用于召回与追溯。

规则：
- 优先提取明确标注了「批号」「Lot」「Batch No.」后面的编号
- 注意：生产批号 ≠ 批准文号，两者不要混淆
- ❌ 批号每批不同，LLM 无法凭知识推断，必须直接来源于 OCR 文本
- ❌ OCR 文本中没有出现任何批号时，返回空 candidates

输出格式：
```json
{"lotNumber": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}
```
无法确定则返回空 candidates，只返回 JSON。
""".trimIndent()

        /**
         * 格式化用户消息（带行号 + 位置 + 可选语音输入）
         *
         * @param fieldLabel 可选，指定提取的字段名（如"药品名称"），为空则用通用说法
         */
        fun formatPositionalUserMessage(
            ocrLines: List<OcrLine>,
            voiceInput: String,
            fieldLabel: String = ""
        ): String {
            val imageHeight = ocrLines.maxOfOrNull { it.boundingBox?.bottom ?: 0 } ?: 0
            val lines = ocrLines.mapIndexed { i, line ->
                "行 ${i + 1} | 位置: ${line.estimateVerticalPosition(imageHeight)} | ${line.text}"
            }
            return formatUserMessage(lines.joinToString("\n"), voiceInput, fieldLabel)
        }

        /**
         * 格式化用户消息（仅行号，无位置信息）
         *
         * @param fieldLabel 可选，指定提取的字段名（如"药品名称"），为空则用通用说法
         */
        fun formatSimpleUserMessage(rawText: String, voiceInput: String, fieldLabel: String = ""): String {
            val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
                .mapIndexed { i, line -> "行 ${i + 1} | $line" }
            return formatUserMessage(lines.joinToString("\n"), voiceInput, fieldLabel)
        }

        /**
         * 格式化用户的输入为 user message 模板
         *
         * @param fieldLabel 可选，指定提取的字段名，为空则用通用说法
         */
        private fun formatUserMessage(ocrText: String, voiceInput: String, fieldLabel: String = ""): String = buildString {
            appendLine("OCR识别文本：")
            appendLine(ocrText)
            appendLine()
            if (voiceInput.isNotBlank()) {
                appendLine("用户语音输入文本（用户对着药品包装说话识别，应优先采纳）：")
                appendLine(voiceInput)
                appendLine()
            }
            val suffix = if (fieldLabel.isNotBlank()) fieldLabel else "药品信息"
            append("从以上信息，提取$suffix。")
        }

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

            // 解析全部字段（基于 FIELD_DEFINITIONS，目前 5 个）
            for (fieldKey in FIELD_DEFINITIONS.keys) {
                fieldMap[fieldKey] = extractFieldCandidates(resultJson, fieldKey)
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
                sb.append("用户语音输入文本（用户对着药品包装说话识别，应优先采纳）：\n")
                sb.append(voiceInput)
                sb.append("\n\n")
            }
            sb.append("从以上信息，提取药品信息。")
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
     * 提取 LLM 返回的 5 字段到 [DrugInfo]（4 字段：approvalNumber + lotNumber 合并为 batchNumber）
     */
    private fun toDrugInfo(rawCandidates: Map<String, FieldCandidates>): DrugInfo {
        val batchCandidates = mutableListOf<Candidate>()
        batchCandidates.addAll(rawCandidates["approvalNumber"]?.candidates ?: emptyList())
        batchCandidates.addAll(rawCandidates["lotNumber"]?.candidates ?: emptyList())

        return DrugInfo(
            drugName = rawCandidates["drugName"]?.bestValue ?: "",
            expiryDate = rawCandidates["expiryDate"]?.bestValue ?: "",
            manufacturer = rawCandidates["manufacturer"]?.bestValue ?: "",
            batchNumber = if (batchCandidates.isEmpty()) ""
                else batchCandidates.maxByOrNull { it.confidence }?.value ?: ""
        )
    }

    /**
     * 全字段提取：从 OCR 文本中提取全部 5 个字段
     *
     * 基于 [FIELD_DEFINITIONS] 动态构建 system prompt，调用一次 API。
     *
     * @param rawText  ML Kit OCR 原始识别文本
     * @param ocrLines OCR 逐行识别结果（含位置信息），为空则走无位置路径
     * @param userVoiceText 用户语音补充文本（与 OCR 文本一起发给 LLM 参考）
     * @return [Result]，其中 [allCandidates] 包含全部 5 个字段
     */
    fun extractDrugInfo(
        rawText: String,
        ocrLines: List<OcrLine> = emptyList(),
        userVoiceText: String = ""
    ): Result {
        if (rawText.isBlank()) {
            return Result(false, error = "OCR 文本为空")
        }

        return try {
            // 1️⃣ 格式化用户消息
            val formattedText = if (ocrLines.isNotEmpty()) {
                formatPositionalUserMessage(ocrLines, userVoiceText)
            } else {
                val filtered = DrugOcrParser.preFilter(rawText)
                formatSimpleUserMessage(filtered, userVoiceText)
            }

            // 2️⃣ 动态构建全字段 system prompt（基于 FIELD_DEFINITIONS）
            // 仅在用户提供了语音补充时才包含语音输入处理规则
            val systemPrompt = buildFullSystemPrompt(hasVoiceInput = userVoiceText.isNotBlank())

            // 3️⃣ 调用 API（含重试）
            val responseJson = callApiWithRetry(formattedText, systemPrompt)

            // 4️⃣ 解析全部 5 个字段
            val rawCandidates = parseResponse(responseJson)

            // 5️⃣ 映射到 DrugInfo（approvalNumber + lotNumber → batchNumber）
            val bestInfo = toDrugInfo(rawCandidates)

            Log.d(TAG, "LLM full result: drugName=[${bestInfo.drugName}] " +
                    "expiry=[${bestInfo.expiryDate}] mfg=[${bestInfo.manufacturer}] batch=[${bestInfo.batchNumber}]")

            Result(
                success = true,
                allCandidates = rawCandidates,
                drugInfo = bestInfo,
                formattedInput = formattedText,
                rawApiResponse = responseJson
            )
        } catch (e: Exception) {
            val userMsg = toUserMessage(e)
            Log.e(TAG, "DeepSeek API call failed: $userMsg", e)
            Result(false, error = userMsg)
        }
    }

    /**
     * 单字段提取：从 OCR 文本中提取指定字段
     *
     * 基于 [FIELD_DEFINITIONS] 中对应字段的 desc 动态构建聚焦 prompt，调用一次 API。
     *
     * @param fieldKey 字段 key（如 "drugName"），必须存在于 [FIELD_DEFINITIONS]
     * @param rawText  ML Kit OCR 原始识别文本
     * @param ocrLines OCR 逐行识别结果（含位置信息），为空则走无位置路径
     * @param userVoiceText 用户语音补充文本（与 OCR 文本一起发给 LLM 参考）
     * @return 该字段的候选列表，可直接取 [bestValue] 获取文本
     */
    fun extractSingleField(
        fieldKey: String,
        rawText: String,
        ocrLines: List<OcrLine> = emptyList(),
        userVoiceText: String = ""
    ): FieldCandidates {
        if (!FIELD_DEFINITIONS.containsKey(fieldKey)) {
            throw IllegalArgumentException("未知字段 key: $fieldKey，可用: ${FIELD_DEFINITIONS.keys}")
        }

        if (rawText.isBlank()) return FieldCandidates()

        return try {
            // 1️⃣ 格式化用户消息（指定字段名，LLM 就不会返回无关字段）
            val fieldLabel = FIELD_DEFINITIONS[fieldKey]?.labelCn ?: ""
            val formattedText = if (ocrLines.isNotEmpty()) {
                formatPositionalUserMessage(ocrLines, userVoiceText, fieldLabel)
            } else {
                val filtered = DrugOcrParser.preFilter(rawText)
                formatSimpleUserMessage(filtered, userVoiceText, fieldLabel)
            }

            // 2️⃣ 动态构建单字段 system prompt
            val systemPrompt = buildSingleFieldSystemPrompt(fieldKey)

            // 3️⃣ 调用 API（含重试）
            val responseJson = callApiWithRetry(formattedText, systemPrompt)

            // 4️⃣ 解析（parseResponse 返回全部字段，但只取目标字段）
            val rawCandidates = parseResponse(responseJson)

            rawCandidates[fieldKey] ?: FieldCandidates()
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek single field [$fieldKey] failed: ${toUserMessage(e)}", e)
            FieldCandidates()
        }
    }

    // ==================== 内部方法 ====================

    /** API 重试次数 */
    private val maxRetries = 3

    /**
     * API 错误分类 —— 决定是否值得重试
     */
    private enum class ErrorClass {
        /** 网络/IO/5xx/429 — 可重试 */
        RETRYABLE,
        /** 400/401/403/404 — 重试也没用 */
        NON_RETRYABLE
    }

    /**
     * 带分类的 API 异常
     */
    private class ApiException(
        val errorClass: ErrorClass,
        message: String,
        val statusCode: Int = 0,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    /**
     * 根据 HTTP 状态码返回用户可读的错误消息
     */
    private fun userFacingError(statusCode: Int, responseBody: String): String = when (statusCode) {
        400 -> "请求格式错误，请联系开发者"
        401, 403 -> "API Key 无效或已过期，请检查 API Key 配置"
        404 -> "LLM 服务地址错误，请联系开发者"
        429 -> "请求过于频繁，请稍后重试"
        in 500..599 -> "LLM 服务暂时不可用，请稍后重试"
        else -> "API 请求失败 (HTTP $statusCode)"
    }

    /**
     * 单次 API 调用（不重试）
     *
     * @throws ApiException 区分可重试/不可重试
     * @throws IOException 网络异常（可重试）
     */
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
            val errorClass = when (responseCode) {
                400, 401, 403, 404 -> ErrorClass.NON_RETRYABLE
                else -> ErrorClass.RETRYABLE  // 429, 5xx
            }
            throw ApiException(
                errorClass = errorClass,
                message = "API error $responseCode: ${response.take(200)}",
                statusCode = responseCode
            )
        }

        return response
    }

    /**
     * 带重试的 API 调用
     *
     * - 不可重试错误（400/401/403/404）→ 立即抛出，不重试
     * - 可重试错误（429/5xx/网络异常）→ 重试 [maxRetries] 次
     * - 重试耗尽 → 抛出最后一次异常
     *
     * @return API 响应 JSON 字符串
     * @throws ApiException 不可重试错误或重试耗尽
     */
    private fun callApiWithRetry(formattedText: String, systemPrompt: String): String {
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                return callApi(formattedText, systemPrompt)
            } catch (e: ApiException) {
                if (e.errorClass == ErrorClass.NON_RETRYABLE) {
                    throw e  // 不可重试，立即失败
                }
                Log.w(TAG, "API call attempt $attempt/$maxRetries failed (retryable), retrying...", e)
                lastError = e
                if (attempt < maxRetries) {
                    val delay = 1000L * attempt  // 递增退避：1s, 2s
                    Thread.sleep(delay)
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "API call attempt $attempt/$maxRetries failed (network), retrying...", e)
                lastError = e
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * attempt)
                }
            }
        }

        throw lastError ?: RuntimeException("API call failed after $maxRetries retries")
    }

    /**
     * 将异常转换为用户可读的错误消息
     */
    private fun toUserMessage(e: Exception): String {
        return when (e) {
            is ApiException -> {
                if (e.statusCode in 400..599) {
                    userFacingError(e.statusCode, e.message ?: "")
                } else {
                    "LLM 提取失败：${e.message?.take(80) ?: "未知错误"}"
                }
            }
            is java.net.SocketTimeoutException -> "网络连接超时，已重试 $maxRetries 次，请检查网络"
            is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络连接"
            is java.io.IOException -> "网络连接失败，已重试 $maxRetries 次：${e.message?.take(60) ?: "未知错误"}"
            else -> "LLM 提取失败：${e.message?.take(80) ?: "未知错误"}"
        }
    }
}
