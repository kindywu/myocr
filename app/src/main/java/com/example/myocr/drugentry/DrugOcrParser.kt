package com.example.myocr.drugentry

import android.util.Log

/**
 * 药品 OCR 文本预过滤器
 *
 * 不再做任何正则字段提取，仅提供基础文本清理。
 * 字段解析完全交由 LLM 处理。
 */
class DrugOcrParser {

    /**
     * 不再做正则解析，直接返回空 DrugInfo。
     * 字段提取全部交由 LLM 处理。
     */
    fun parse(rawText: String): DrugInfo {
        if (rawText.isBlank()) return DrugInfo()
        Log.d(TAG, "Regex parsing disabled — all fields rely on LLM")
        return DrugInfo()
    }

    companion object {
        private const val TAG = "DrugOcrParser"

        /** 药品名称最短字符数（备用，保留供外部引用） */
        const val MIN_DRUG_NAME_LEN = 3
        /** 药品名称最长字符数 */
        const val MAX_DRUG_NAME_LEN = 50

        /** 已知的药品剂型后缀（备用） */
        val VALID_DRUG_SUFFIXES = listOf(
            "胶囊", "片", "颗粒", "口服液", "注射液", "滴眼液",
            "软膏", "乳膏", "喷雾", "气雾剂", "贴膏", "贴片",
            "分散片", "缓释片", "控释片", "肠溶片", "咀嚼片",
            "泡腾片", "含片", "溶液", "混悬液", "糖浆",
            "滴丸", "软胶囊", "丸", "散", "冲剂"
        )

        /** 明显不是药品名称的关键词（备用） */
        val SKIP_KEYWORDS = listOf(
            "生产", "批号", "日期", "有效期", "存储", "保存", "注意", "禁忌",
            "国药准字", "批准文号", "注册证号", "进口药品",
            "说明书", "阅读", "不良反应", "用法用量", "贮藏",
            "规格", "包装", "性状", "药物相互作用", "药理毒理",
            "药代动力学", "儿童用药", "老年用药", "孕妇及哺乳期",
            "药准字", "执行标准", "产品批号", "生产日期",
            "【", "产品名称"
        )

        private val PURE_NUMBER_REGEX = Regex("""^\d+$""")

        /**
         * 预过滤：在送入 LLM 前剔除明显无关的行
         *
         * @param rawText OCR 原始文本
         * @return 过滤后的文本（每行保留原始分行）
         */
        fun preFilter(rawText: String): String {
            val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
            val filtered = lines.filter { line ->
                line.length in 1..100 &&
                    !PURE_NUMBER_REGEX.matches(line) &&
                    line.any { it in '一'..'鿿' }
            }
            return filtered.joinToString("\n")
        }
    }
}
