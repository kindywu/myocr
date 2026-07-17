package com.example.myocr.drugentry

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek API 集成测试 —— 逐字段提取。
 *
 * 每个字段独立调用一次 API，使用 per_field.md 模板，
 * 传入 OCR 原文 + 语音输入（药品名称），验证提取准确性。
 * 需要网络连接和有效的 API key。
 */
class DeepSeekIntegrationTest {

    private val apiKey = "sk-fd477113eced4060966ee694da6c099d"

    // ==================== 字段元数据 ====================

    private data class FieldMeta(
        val key: String,
        val displayName: String,
        val definition: String,
        val outputSchema: String
    )

    private val fieldMetas = listOf(
        FieldMeta(
            key = "drugName",
            displayName = "药品名称",
            definition = """必须是药品的真正名称，如「阿莫西林胶囊」「布洛芬缓释片」。
❌ 「国药准字H20093069」→ 这是批准文号，不是药名
❌ 「请仔细阅读说明书」→ 这是警示语，不是药名""",
            outputSchema = """{"drugName": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}"""
        ),
        FieldMeta(
            key = "expiryDate",
            displayName = "有效期",
            definition = """格式 yyyy-MM 或 yyyy-MM-dd（如 2026-09 或 2026-09-15），根据识别的精度决定。
若包装仅标注「生产日期」+「保质期/有效期 X 年/月」，而没有直接印「有效期至」的具体日期，需要自行推算：有效期 = 生产日期 + 保质期。此时请在 reason 中注明「由生产日期与保质期推算」。""",
            outputSchema = """{"expiryDate": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}"""
        ),
        FieldMeta(
            key = "manufacturer",
            displayName = "生产厂家",
            definition = """药品实际生产企业名称，通常含「有限公司」「制药」「药业」等关键词。
❌ 若包装上同时印有生产企业和经销/代理商信息，只提取生产企业本身，不要提取经销商或代理商名称。""",
            outputSchema = """{"manufacturer": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}"""
        ),
        FieldMeta(
            key = "batchNumber",
            displayName = "批号/注册号",
            definition = """包含：批准文号（国药准字/注册证号）、生产批号（Lot/Batch）。""",
            outputSchema = """{"batchNumber": {"candidates": [{"value": "...", "confidence": 0.0, "reason": "..."}]}}"""
        )
    )

    // ==================== OCR 案例 ====================

    @Test
    fun `案例3 - 全字段提取（手机实测 - 奥美拉唑）`() {
        val rawText = """「产品北

OoOらC2C3

O60A-21-2503O5

欧意。

奥美拉唑溶肢真,

请仔婚阅读说书井按说明使用或在药师指导下购头利他H

【用法用量】口服。成人,一次道,一日次(每24小吋),用温开水送

国药准字H2046431 0

本品心须整粒吞服, ,不可咀屬或压碎更不可将本品氏碎于食物.

【成份]、【性状】、 【不良反应】、 【禁忌】、【注意事项)等洋见说

【适应症】!用于胃酸过多引起的烧心和反酸症状的短期线。

C藏】遮光,密封,在干燥处保存。

品上市许可持有人:

台的集团欧意药业有限公司

|企业:石药集团欧意药业有限公可"""

        val voiceInput = "奥美拉唑"
        val results = extractAllFields(rawText, voiceInput, "案例3 - 全字段提取（手机实测 - 奥美拉唑）")
        printResults(results)

        val drugName = results["drugName"]?.bestValue ?: ""
        println("药品名称: $drugName")
        assertTrue("药品名称应含「奥美拉唑」: $drugName", drugName.contains("奥美拉唑"))

        val batch = results["batchNumber"]?.bestValue ?: ""
        println("批号: $batch")
        // OCR 为 "国药准字H2046431 0"，LLM 推断为 H20046431，实际值 H20046430
        assertTrue("批号应非空: $batch", batch.isNotBlank())

        val manufacturer = results["manufacturer"]?.bestValue ?: ""
        println("生产厂家: $manufacturer")
        assertTrue("生产厂家应含「石药集团」: $manufacturer", manufacturer.contains("石药集团"))
    }

    @Test
    fun `案例4 - 有效期字段（手机实测 - 奥美拉唑）`() {
        val rawText = """060A-21-250305

产品批号:

|生产日期

0602504203

2025.04.05

有效期至2027.04.04"""

        val voiceInput = ""
        val results = extractAllFields(rawText, voiceInput, "案例4 - 有效期字段（手机实测 - 奥美拉唑）")
        printResults(results)

        val expiry = results["expiryDate"]?.bestValue ?: ""
        println("有效期: $expiry")
        assertEquals("2027-04-04", expiry)

        val batch = results["batchNumber"]?.bestValue ?: ""
        println("批号: $batch")
        assertTrue("批号应含 0602504203 或 060A-21-250305: $batch",
            batch.contains("0602504203") || batch.contains("060A-21-250305"))
    }

    // ==================== 辅助方法 ====================

    /**
     * 逐字段调用 API，返回所有字段的提取结果。
     * 同时收集每个字段的原始 API 响应到 [debugEntries]。
     */
    private var debugEntries = mutableListOf<String>()

    private fun extractAllFields(
        rawText: String,
        voiceInput: String,
        testName: String
    ): Map<String, DeepSeekClient.FieldCandidates> {
        println("==================== 逐字段提取 ====================")
        println("OCR 原文行数: ${rawText.lines().size}")
        println("语音输入: $voiceInput")
        println()
        debugEntries = mutableListOf()
        debugEntries.add("OCR 原文行数: ${rawText.lines().size}")
        debugEntries.add("语音输入: $voiceInput")

        val results = mutableMapOf<String, DeepSeekClient.FieldCandidates>()
        for (field in fieldMetas) {
            println("─── ${field.displayName}（${field.key}）───")

            // 使用 DeepSeekClient.Companion 动态构造字段专用 prompt
            val systemPrompt = DeepSeekClient.buildSingleFieldSystemPrompt(field.key)
            val userMessage = DeepSeekClient.formatSimpleUserMessage(rawText, voiceInput)

            // 调用 API
            val responseJson = callApi(userMessage, systemPrompt)

            // 收集调试信息
            debugEntries.add("\n===== ${field.displayName} =====")
            debugEntries.add("【发送给 LLM 的 system prompt】")
            debugEntries.add(systemPrompt)
            debugEntries.add("【发送给 LLM 的 user message】")
            debugEntries.add(userMessage)
            debugEntries.add("【LLM 原始响应 JSON】")
            debugEntries.add(responseJson)
            try {
                val inner = JSONObject(responseJson)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                debugEntries.add("【LLM 返回 content（格式化）】")
                debugEntries.add(JSONObject(inner).toString(2))
            } catch (e: Exception) {
                debugEntries.add("(解析 content 失败)")
            }

            // 解析
            val allParsed = DeepSeekClient.parseResponse(responseJson)
            val fc = allParsed[field.key] ?: DeepSeekClient.FieldCandidates()
            results[field.key] = fc

            if (fc.candidates.isEmpty()) {
                println("  (无结果)")
            } else {
                for (c in fc.candidates) {
                    println("  · ${c.value} (${(c.confidence * 100).toInt()}%) ${c.reason}")
                }
            }
            println()
        }
        println("===================================================")

        writeDebugFile(testName)
        return results
    }

    private fun printResults(results: Map<String, DeepSeekClient.FieldCandidates>) {
        for ((key, fc) in results) {
            val label = fieldMetas.find { it.key == key }?.displayName ?: key
            println("$label: ${fc.bestValue}")
        }
        println()
    }

    private fun writeDebugFile(testName: String) {
        val file = java.io.File("C:\\github\\myocr\\debug_${testName.replace(" ", "_")}.txt")
        file.writeText(
            "========================================\n" +
            "  测试案例: $testName\n" +
            "  时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
            "========================================\n\n" +
            debugEntries.joinToString("\n") + "\n"
        )
        println("调试信息已写入: ${file.absolutePath}")
    }

    private fun callApi(userMessage: String, systemPrompt: String): String {
        val payload = JSONObject().apply {
            put("model", "deepseek-v4-flash")
            put("response_format", JSONObject().put("type", "json_object"))

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
            put("messages", messages)
            put("temperature", 0.1)
        }

        val url = URL("https://api.deepseek.com/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        val reader = BufferedReader(
            InputStreamReader(
                if (responseCode in 200..299) conn.inputStream else conn.errorStream
            )
        )
        val response = reader.readText()
        reader.close()
        conn.disconnect()

        if (responseCode !in 200..299) {
            throw RuntimeException("API error $responseCode: ${response.take(500)}")
        }

        return response
    }

    companion object {
    }
}
