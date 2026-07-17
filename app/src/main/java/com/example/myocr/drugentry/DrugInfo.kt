package com.example.myocr.drugentry

/**
 * 药品信息数据模型
 *
 * 对应 prototype 中四个核心字段：
 * - 药品名称（必填）
 * - 有效期至
 * - 生产厂家
 * - 批号/注册号
 */
data class DrugInfo(
        val drugName: String = "",
        val expiryDate: String = "",
        val manufacturer: String = "",
        val batchNumber: String = ""
) {
    /** 是否所有必填字段都已填写 */
    val isComplete: Boolean
        get() = drugName.isNotBlank()

    /** 是否有任何字段有值 */
    val hasAnyValue: Boolean
        get() =
                drugName.isNotBlank() ||
                        expiryDate.isNotBlank() ||
                        manufacturer.isNotBlank() ||
                        batchNumber.isNotBlank()

    /** 合并两组药品信息（新值覆盖旧值） */
    fun mergeWith(latest: DrugInfo): DrugInfo =
            copy(
                    drugName = latest.drugName.ifBlank { drugName },
                    expiryDate = latest.expiryDate.ifBlank { expiryDate },
                    manufacturer = latest.manufacturer.ifBlank { manufacturer },
                    batchNumber = latest.batchNumber.ifBlank { batchNumber }
            )

    /** 返回非空字段的数量 */
    val filledFieldCount: Int
        get() {
            var count = 0
            if (drugName.isNotBlank()) count++
            if (expiryDate.isNotBlank()) count++
            if (manufacturer.isNotBlank()) count++
            if (batchNumber.isNotBlank()) count++
            return count
        }

    companion object {
        const val TOTAL_FIELDS = 4
    }
}
