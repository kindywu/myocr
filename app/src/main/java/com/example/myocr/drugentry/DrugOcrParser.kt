package com.example.myocr.drugentry

import android.util.Log

/**
 * 药品 OCR 文本解析器
 *
 * 将 ML Kit 原始识别文本中的结构化药品信息提取出来。
 * 使用正则 + 规则的方式匹配常见药品包装文字模式。
 */
class DrugOcrParser {

    /**
     * 从原始 OCR 文本中提取药品结构字段
     *
     * @param rawText ML Kit 返回的完整识别文本
     * @return 解析出的药品信息（未匹配到的字段为空字符串）
     */
    fun parse(rawText: String): DrugInfo {
        if (rawText.isBlank()) return DrugInfo()

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val drugName = extractDrugName(lines, rawText)
        val expiryDate = extractExpiryDate(rawText)
        val manufacturer = extractManufacturer(lines)
        val batchNumber = extractBatchNumber(rawText)

        val result = DrugInfo(
            drugName = drugName,
            expiryDate = expiryDate,
            manufacturer = manufacturer,
            batchNumber = batchNumber
        )

        Log.d(TAG, "Parsed: drugName=[$drugName] expiry=[$expiryDate] mfg=[$manufacturer] batch=[$batchNumber]")
        return result
    }

    /**
     * 提取药品名称
     *
     * 策略：查找常见药品名称后缀（胶囊、片、颗粒、口服液等）
     * 取包含这些后缀的行。兜底用最短的候选行（非匹配行中
     * 排除明显不是药名的关键词后取最短——药名通常不长）。
     */
    private fun extractDrugName(lines: List<String>, rawText: String): String {
        // 1️⃣ 精确匹配：找含剂型后缀的行
        for (line in lines) {
            for (suffix in VALID_DRUG_SUFFIXES) {
                if (line.contains(suffix) && line.length in MIN_DRUG_NAME_LEN..MAX_DRUG_NAME_LEN) {
                    return line
                }
            }
        }

        // 2️⃣ 兜底：找最短的非噪声行（避免「最长行」拿到批准文号）
        val candidates = lines.filter { line ->
            line.length in MIN_DRUG_NAME_LEN..MAX_DRUG_NAME_LEN &&
                SKIP_KEYWORDS.none { line.contains(it) } &&
                !line.matches(Regex("""^[\d./\-:()（）、，,]+${'$'}""")) // 纯数字/标点
        }
        // 最短候选行 — 药名通常不会太长，且避开大段的警示语
        val best = candidates.minByOrNull { it.length }
        if (best != null) return best

        return ""
    }

    // ==================== 公共工具方法（供 LLM 后验证使用）====================

    /**
     * 提取有效期/失效期
     *
     * 匹配策略（按优先级）：
     * 1. 精确匹配「有效期至/EXP + 分隔符 + 日期」——分隔符包括：冒号、点、空格、逗号等
     * 2. 找「有效期」关键词附近的日期（同一行或下一行）
     * 3. 兜底：找任意 YYYY-MM 格式日期，但排除「生产日期」后面的
     */
    private fun extractExpiryDate(rawText: String): String {
        val sep = """[：:.,，\s]*"""

        // 1a) keyword prefix + full date yyyy-MM-dd
        val kwFull = Regex("""(?:有效期(?:至|到|截止)|失效期|EXP|exp)${sep}(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
        val mFull = kwFull.find(rawText)
        if (mFull != null) {
            val y = mFull.groupValues[1]; val m = mFull.groupValues[2].padStart(2, '0'); val d = mFull.groupValues[3].padStart(2, '0')
            if (y.toIntOrNull() in 2020..2040 && m.toIntOrNull() in 1..12 && d.toIntOrNull() in 1..31) return "$y-$m-$d"
        }

        // 1b) keyword prefix + yyyy-MM only (not followed by -dd)
        val kwMonth = Regex("""(?:有效期(?:至|到|截止)|失效期|EXP|exp)${sep}(\d{4})[-/.](\d{1,2})(?!\s*[-/.]\s*\d)""")
        val mMonth = kwMonth.find(rawText)
        if (mMonth != null) {
            val y = mMonth.groupValues[1]; val m = mMonth.groupValues[2].padStart(2, '0')
            if (y.toIntOrNull() in 2020..2040 && m.toIntOrNull() in 1..12) return "$y-$m"
        }

        // 2) find line containing "有效期", extract date from it (full date first)
        for (line in rawText.lines().map { it.trim() }) {
            if (!line.contains("有效期")) continue
            val f = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""").find(line)
            if (f != null) {
                val y = f.groupValues[1]; val m = f.groupValues[2].padStart(2, '0'); val d = f.groupValues[3].padStart(2, '0')
                if (y.toIntOrNull() in 2020..2040 && m.toIntOrNull() in 1..12 && d.toIntOrNull() in 1..31) return "$y-$m-$d"
            }
            val mo = Regex("""(\d{4})[-/.](\d{1,2})(?!\s*[-/.]\s*\d)""").find(line)
            if (mo != null) {
                val y = mo.groupValues[1]; val m = mo.groupValues[2].padStart(2, '0')
                if (y.toIntOrNull() in 2020..2040 && m.toIntOrNull() in 1..12) return "$y-$m"
            }
        }

        // 3) generic fallback: find any date pattern, skip "生产日期"
        val genericPatterns = listOf(
            Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})"""), // 2026-09-15 (full date)
            Regex("""(\d{4})[-/.](\d{1,2})""")                 // 2026-09 (month only)
        )
        for (pattern in genericPatterns) {
            val matches = pattern.findAll(rawText).toList()
            for (match in matches) {
                val groups = match.groupValues
                if (groups.size >= 3) {
                    val year = groups[1].toIntOrNull() ?: continue
                    val month = groups[2].padStart(2, '0')
                    // full date (yyyy-MM-dd) or month only?
                    val candidate = if (groups.size >= 4 && groups[3].isNotBlank()) {
                        val day = groups[3].padStart(2, '0')
                        if (day.toIntOrNull() !in 1..31) continue
                        "$year-$month-$day"
                    } else {
                        "$year-$month"
                    }

                    // 验证日期合理性
                    if (year in 2020..2040 && month.toIntOrNull() in 1..12) {
                        // 检查这个日期前后是否有「生产日期」，有则跳过
                        val matchStart = match.range.first
                        val contextBefore = rawText.substring(maxOf(0, matchStart - 8), matchStart)
                        if ("生产日期" in contextBefore || "生产" in contextBefore) {
                            continue  // 跳过生产日期
                        }
                        return candidate
                    }
                }
            }
        }

        return ""
    }

    /**
     * 提取生产厂家
     *
     * 匹配模式：生产厂家/生产企业/制造商 + 公司名
     */
    private fun extractManufacturer(lines: List<String>): String {
        val prefixPatterns = listOf(
            Regex("""(?:生产[厂家企业]|制造商|厂家|Manufacturer)[：:\s]*(.+)"""),
            Regex("""(.+(?:制药|药业|生物|医药|集团|有限公司|股份))""")
        )

        // 优先找带前缀的行
        for (line in lines) {
            for (pattern in prefixPatterns) {
                val match = pattern.find(line)
                if (match != null) {
                    val value = if (match.groupValues.size > 1) match.groupValues[1].trim() else line.trim()
                    if (value.length in 4..60 && !value.contains("生产") && !value.contains("批号")) {
                        return value
                    }
                }
            }
        }

        // 兜底：找包含"有限公司"的行（去掉已识别的名称行）
        for (line in lines) {
            if (line.contains("有限公司") || line.contains("股份")) {
                return line
            }
        }

        // 兜底：找包含"制药"或"药业"的行
        for (line in lines) {
            if (line.contains("制药") || line.contains("药业") || line.contains("生物")) {
                return line
            }
        }

        return ""
    }

    /**
     * 提取批号/注册号
     *
     * 匹配模式：批号/国药准字/注册号 + 字母数字组合
     */
    private fun extractBatchNumber(rawText: String): String {
        val patterns = listOf(
            Regex("""(?:批号|产品批号|Lot|lot|LOT)[：:\s]*([A-Za-z0-9]+)"""),
            Regex("""(?:国药准字|注册证号|批准文号)[：:\s]*([A-Za-z0-9]+)"""),
            Regex("""(H\d{8})"""),  // 国药准字 H 开头 8 位数字
            Regex("""([A-Z]{1,2}\d{6,9})""")  // 通用批号格式
        )

        for (pattern in patterns) {
            val match = pattern.find(rawText)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }

        return ""
    }

    companion object {
        private const val TAG = "DrugOcrParser"

        /** 药品名称最短字符数 */
        const val MIN_DRUG_NAME_LEN = 3
        /** 药品名称最长字符数 */
        const val MAX_DRUG_NAME_LEN = 50

        /** 已知的药品剂型后缀 */
        val VALID_DRUG_SUFFIXES = listOf(
            "胶囊", "片", "颗粒", "口服液", "注射液", "滴眼液",
            "软膏", "乳膏", "喷雾", "气雾剂", "贴膏", "贴片",
            "分散片", "缓释片", "控释片", "肠溶片", "咀嚼片",
            "泡腾片", "含片", "溶液", "混悬液", "糖浆",
            "滴丸", "软胶囊", "丸", "散", "冲剂"
        )

        /** 明显不是药品名称的关键词（用于去噪和 LLM 结果验证） */
        val SKIP_KEYWORDS = listOf(
            "生产", "批号", "日期", "有效期", "存储", "保存", "注意", "禁忌",
            "国药准字", "批准文号", "注册证号", "进口药品",
            "说明书", "阅读", "不良反应", "用法用量", "贮藏",
            "规格", "包装", "性状", "药物相互作用", "药理毒理",
            "药代动力学", "儿童用药", "老年用药", "孕妇及哺乳期",
            "药准字", "执行标准", "产品批号", "生产日期",
            "【", "产品名称"
        )

        private val APPROVAL_CODE_REGEX = Regex("""^(国药准字|国药进字|国药试字|注册证号|批准文号)[A-Za-z0-9]+${'$'}""")
        private val PURE_NUMBER_REGEX = Regex("""^\d+${'$'}""")

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

        /**
         * 验证 LLM 返回的药品名称候选是否合理
         */
        fun isValidDrugName(candidate: String): Boolean {
            if (candidate.isBlank()) return false
            if (candidate.length < MIN_DRUG_NAME_LEN || candidate.length > MAX_DRUG_NAME_LEN) return false
            if (APPROVAL_CODE_REGEX.matches(candidate)) return false
            if (SKIP_KEYWORDS.any { candidate.contains(it) }) return false
            if (PURE_NUMBER_REGEX.matches(candidate)) return false
            return candidate.any { it in '一'..'鿿' }
        }

        /**
         * 验证有效期格式（支持 yyyy-MM 和 yyyy-MM-dd）
         */
        fun isValidExpiryDate(candidate: String): Boolean {
            if (candidate.isBlank()) return false
            // yyyy-MM-dd 或 yyyy-MM
            val match = Regex("""^(\d{4})-(\d{2})(?:-(\d{2}))?$""").find(candidate) ?: return false
            val year = match.groupValues[1].toIntOrNull() ?: return false
            val month = match.groupValues[2].toIntOrNull() ?: return false
            if (year !in 2020..2040 || month !in 1..12) return false
            // 如果有日，验证日范围
            val day = match.groupValues[3]
            if (day.isNotBlank() && day.toIntOrNull() !in 1..31) return false
            return true
        }

        /**
         * 验证生产厂家是否合理
         */
        fun isValidManufacturer(candidate: String): Boolean {
            if (candidate.isBlank()) return false
            if (candidate.length < 4 || candidate.length > 60) return false
            val companyKeywords = listOf("有限公司", "股份", "制药", "药业", "生物",
                "集团", "药厂", "医药", "厂", "公司")
            return companyKeywords.any { candidate.contains(it) }
        }

        /**
         * 验证批号/注册号是否合理
         */
        fun isValidBatchNumber(candidate: String): Boolean {
            if (candidate.isBlank()) return false
            return candidate.length in 4..30
        }
    }
}
