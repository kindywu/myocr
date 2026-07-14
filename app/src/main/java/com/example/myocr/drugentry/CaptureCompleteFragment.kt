package com.example.myocr.drugentry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.myocr.R

/**
 * 多角度补全进度页
 *
 * 展示各字段的识别状态，支持：
 * - 查看已识别字段的值
 * - 对任意字段单独补拍（字段级采集，不影响其他字段）
 * - 如果 LLM 返回了多候选，点击字段值可切换选择
 * - 继续拍摄更多角度（全字段采集）
 * - 查看结果进入人工确认页
 */
class CaptureCompleteFragment : Fragment() {

    private var _binding: com.example.myocr.databinding.FragmentCaptureCompleteBinding? = null
    private val binding get() = _binding!!

    /** 字段 key → 用户可选择的候选值列表 */
    private var selectableCandidates: Map<String, List<String>> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = com.example.myocr.databinding.FragmentCaptureCompleteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as DrugEntryActivity
        val session = activity.session

        // 从 session 读取 LLM 多候选
        selectableCandidates = buildSelectableCandidates(session)

        // 返回
        binding.backButton.setOnClickListener { activity.supportFragmentManager.popBackStack() }

        // 更新进度和字段状态
        updateProgress(session)
        updateFieldStatus(activity, session)

        // 字段级补拍：只拍该字段，不影响其他已有值
        binding.drugNameCapture.setOnClickListener {
            activity.startRetake("drugName", DrugEntryStep.COMPLETION)
        }
        binding.expiryCapture.setOnClickListener {
            activity.startRetake("expiryDate", DrugEntryStep.COMPLETION)
        }
        binding.manufacturerCapture.setOnClickListener {
            activity.startRetake("manufacturer", DrugEntryStep.COMPLETION)
        }
        binding.batchCapture.setOnClickListener {
            activity.startRetake("batchNumber", DrugEntryStep.COMPLETION)
        }

        // 继续拍摄：全字段采集（可覆盖已有值）
        binding.continueCaptureButton.setOnClickListener {
            activity.startRetake(null, DrugEntryStep.COMPLETION)
        }

        // 查看结果 → 进入人工确认
        binding.viewResultButton.setOnClickListener { activity.navigateTo(DrugEntryStep.CONFIRM) }

        // 调试：查看原始 OCR 文本
        binding.debugRawOcr.setOnClickListener { showOcrDebugDialog(activity) }
        binding.debugRawOcr.visibility = if (session.rawOcrText.isNotBlank()) View.VISIBLE else View.GONE
    }

    /**
     * 从 session 的 LLM 多候选数据中提取可选值列表
     */
    private fun buildSelectableCandidates(session: DrugEntrySession): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        for ((fieldKey, fieldCandidates) in session.llmCandidates) {
            val values = fieldCandidates.candidates
                .filter { it.value.isNotBlank() }
                .map { it.value }
                .distinct()
            if (values.size > 1) {
                result[fieldKey] = values
            }
        }
        return result
    }

    private fun updateProgress(session: DrugEntrySession) {
        val filled = session.drugInfo.filledFieldCount
        val total = DrugInfo.TOTAL_FIELDS

        binding.progressText.text = getString(R.string.completion_progress, filled, total)
        binding.progressBar.progress = (filled * 100) / total
    }

    private fun updateFieldStatus(activity: DrugEntryActivity, session: DrugEntrySession) {
        val info = session.drugInfo

        updateSingleField(
            activity = activity,
            fieldKey = "drugName",
            fieldValue = info.drugName,
            valueView = binding.drugNameValue,
            iconView = binding.drugNameIcon,
            pendingView = binding.drugNamePending,
            captureButton = binding.drugNameCapture,
            cardView = binding.drugNameCard
        )

        updateSingleField(
            activity = activity,
            fieldKey = "expiryDate",
            fieldValue = info.expiryDate,
            valueView = binding.expiryValue,
            iconView = binding.expiryIcon,
            pendingView = binding.expiryPending,
            captureButton = binding.expiryCapture,
            cardView = binding.expiryCard
        )

        updateSingleField(
            activity = activity,
            fieldKey = "manufacturer",
            fieldValue = info.manufacturer,
            valueView = binding.manufacturerValue,
            iconView = binding.manufacturerIcon,
            pendingView = binding.manufacturerPending,
            captureButton = binding.manufacturerCapture,
            cardView = binding.manufacturerCard
        )

        updateSingleField(
            activity = activity,
            fieldKey = "batchNumber",
            fieldValue = info.batchNumber,
            valueView = binding.batchValue,
            iconView = binding.batchIcon,
            pendingView = binding.batchPending,
            captureButton = binding.batchCapture,
            cardView = binding.batchCard
        )
    }

    /**
     * 更新单个字段的显示状态
     *
     * 拍照按钮始终可见，方便用户随时纠错。
     * 有值的字段显示绿色 ✅ 标记 + 值 + 品牌色背景
     * 空字段显示灰色 📷 提示 + 橙色背景
     * 如果 LLM 有多个候选，值可点击弹出选择器
     */
    private fun updateSingleField(
        activity: DrugEntryActivity,
        fieldKey: String,
        fieldValue: String,
        valueView: View,
        iconView: View,
        pendingView: View,
        captureButton: View,
        cardView: View
    ) {
        if (fieldValue.isNotBlank()) {
            (valueView as? android.widget.TextView)?.text = fieldValue
            valueView.visibility = View.VISIBLE
            iconView.visibility = View.VISIBLE
            pendingView.visibility = View.GONE
            cardView.setBackgroundResource(R.color.field_recognized_bg)

            // 如果 LLM 有多个候选，让值可点击切换
            val candidates = selectableCandidates[fieldKey]
            if (!candidates.isNullOrEmpty()) {
                valueView.isClickable = true
                valueView.setOnClickListener {
                    showCandidatePicker(activity, fieldKey, fieldValue, candidates)
                }
                // 视觉提示：附带候选箭头样式（通过 setCompoundDrawables 加小箭头）
                // 实际运行时，值文本变为可点击状态且有反馈
            } else {
                valueView.isClickable = false
                valueView.setOnClickListener(null)
            }
        } else {
            valueView.visibility = View.GONE
            iconView.visibility = View.GONE
            pendingView.visibility = View.VISIBLE
            cardView.setBackgroundResource(R.color.field_pending_bg)
            valueView.isClickable = false
            valueView.setOnClickListener(null)
        }
        // 拍照按钮始终可见
        captureButton.visibility = View.VISIBLE
    }

    /**
     * 展示 LLM 多候选选择对话框
     *
     * @param activity  宿主 Activity
     * @param fieldKey  当前字段 key
     * @param currentValue 当前已选值
     * @param candidates    LLM 返回的候选值列表
     */
    private fun showCandidatePicker(
        activity: DrugEntryActivity,
        fieldKey: String,
        currentValue: String,
        candidates: List<String>
    ) {
        val fieldLabel = getFieldLabel(fieldKey)
        val items = candidates.toTypedArray()
        var selectedIndex = candidates.indexOf(currentValue)
        if (selectedIndex < 0) selectedIndex = 0

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择 $fieldLabel")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                selectedIndex = which
            }
            .setPositiveButton("确定") { dialog, _ ->
                val chosen = candidates.getOrNull(selectedIndex) ?: return@setPositiveButton
                if (chosen != currentValue) {
                    // 更新 session 中的药品信息
                    val current = activity.session.drugInfo
                    val updated = when (fieldKey) {
                        "drugName" -> current.copy(drugName = chosen)
                        "expiryDate" -> current.copy(expiryDate = chosen)
                        "manufacturer" -> current.copy(manufacturer = chosen)
                        "batchNumber" -> current.copy(batchNumber = chosen)
                        else -> current
                    }
                    // 用 RECOGNIZED 状态（用户从候选列表选的，相当于接受了 OCR 结果）
                    activity.updateDrugInfo(updated, FieldStatus.RECOGNIZED)
                    // 刷新当前页面的显示
                    updateFieldStatus(activity, activity.session)
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 获取字段的中文标签
     */
    private fun getFieldLabel(fieldKey: String): String {
        return when (fieldKey) {
            "drugName" -> "药品名称"
            "expiryDate" -> "有效期"
            "manufacturer" -> "生产厂家"
            "batchNumber" -> "批号"
            else -> fieldKey
        }
    }

    /**
     * 显示 OCR 调试对话框：OCR 原文 + 正则解析 + LLM 候选
     */
    private fun showOcrDebugDialog(activity: DrugEntryActivity) {
        val session = activity.session
        val rawText = session.rawOcrText.ifBlank { "(无 OCR 原文)" }

        // 重新用正则解析一遍，展示结果
        val regexResult = activity.drugOcrParser.parse(rawText)

        val sb = StringBuilder()
        sb.append("━━━ OCR 原始文本 ━━━\n")
        sb.append(rawText.take(800))
        if (rawText.length > 800) sb.append("\n…(截断)")
        sb.append("\n\n")

        sb.append("━━━ 正则解析结果 ━━━\n")
        sb.append("药品名称: ").append(regexResult.drugName.ifBlank { "(空)" }).append("\n")
        sb.append("有效期:   ").append(regexResult.expiryDate.ifBlank { "(空)" }).append("\n")
        sb.append("生产厂家: ").append(regexResult.manufacturer.ifBlank { "(空)" }).append("\n")
        sb.append("批号:     ").append(regexResult.batchNumber.ifBlank { "(空)" }).append("\n")
        sb.append("\n")

        // 最终填到表单的值
        val finalInfo = session.drugInfo
        sb.append("━━━ 当前表单值 ━━━\n")
        sb.append("药品名称: ").append(finalInfo.drugName.ifBlank { "(空)" }).append("\n")
        sb.append("有效期:   ").append(finalInfo.expiryDate.ifBlank { "(空)" }).append("\n")
        sb.append("生产厂家: ").append(finalInfo.manufacturer.ifBlank { "(空)" }).append("\n")
        sb.append("批号:     ").append(finalInfo.batchNumber.ifBlank { "(空)" }).append("\n")

        // LLM 候选
        if (session.llmCandidates.isNotEmpty()) {
            sb.append("\n━━━ LLM 候选 ━━━\n")
            for ((fieldKey, fieldCandidates) in session.llmCandidates) {
                val label = getFieldLabel(fieldKey)
                if (fieldCandidates.candidates.isNotEmpty()) {
                    sb.append("$label:\n")
                    for (c in fieldCandidates.candidates) {
                        sb.append("  · ${c.value} (${(c.confidence * 100).toInt()}%")
                        if (c.reason.isNotBlank()) sb.append(", ${c.reason}")
                        sb.append(")\n")
                    }
                }
            }
        }

        val message = sb.toString()

        // 使用可滚动的对话框展示
        val scrollView = android.widget.ScrollView(requireContext())
        val textView = android.widget.TextView(requireContext()).apply {
            this.text = message
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(4f, 1f)
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)  // 允许长按复制
        }
        scrollView.addView(textView)
        scrollView.minimumHeight = 400

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("识别调试信息")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
