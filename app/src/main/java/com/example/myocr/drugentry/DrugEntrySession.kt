package com.example.myocr.drugentry

import android.net.Uri

/**
 * 字段识别状态
 */
enum class FieldStatus {
    /** 尚未拍摄/识别 */
    PENDING,
    /** OCR 已识别出值 */
    RECOGNIZED,
    /** 用户手动填写 */
    MANUAL,
    /** 用户已确认 */
    CONFIRMED
}

/**
 * 采集会话状态
 *
 * 贯穿药品录入流程的共享状态，由 DrugEntryActivity 持有，
 * Fragment 通过 Activity 引用读写。
 */
data class DrugEntrySession(
    /** 当前步骤 */
    val currentStep: DrugEntryStep = DrugEntryStep.ENTRY,

    /** 各字段的识别状态 */
    val fieldStatuses: Map<String, FieldStatus> = mapOf(
        "drugName" to FieldStatus.PENDING,
        "expiryDate" to FieldStatus.PENDING,
        "manufacturer" to FieldStatus.PENDING,
        "batchNumber" to FieldStatus.PENDING
    ),

    /** 已拍摄的照片路径列表 */
    val capturedPhotos: List<Uri> = emptyList(),

    /** 当前已识别的药品信息 */
    val drugInfo: DrugInfo = DrugInfo(),

    /** 录入来源 */
    val origin: EntryOrigin = EntryOrigin.OCR,

    /** 当前拍照轮次（引导 3 轮完整覆盖） */
    val captureRound: Int = 0,

    /** 重拍目标字段（从确认页/视频采集页点击重拍时设置） */
    val retakeFieldTarget: String? = null,

    /** 重拍来源步骤（用于拍完后跳回正确的页面） */
    val retakeSource: DrugEntryStep? = null,

    /** 待裁剪的照片路径（拍照后先裁剪再 OCR） */
    val pendingPhotoPath: String = "",

    /** OCR 原始识别文本（用于调试展示） */
    val rawOcrText: String = "",

    /** LLM 原始响应 JSON（用于调试展示） */
    val llmResponseJson: String = ""
)

enum class DrugEntryStep {
    /** 录入方式选择 */
    ENTRY,
    /** 拍照采集 */
    CAPTURE,
    /** 选区裁剪（拍照后，OCR 前） */
    CROP,
    /** 多角度补全 */
    COMPLETION,
    /** 信息确认 */
    CONFIRM,
    /** 手动录入 */
    MANUAL,
    /** 保存完成 */
    SAVED
}

enum class EntryOrigin {
    /** 拍照识别为主路径 */
    OCR,
    /** 手动填写为兜底 */
    MANUAL,
    /** 混合路径 */
    MIXED
}
