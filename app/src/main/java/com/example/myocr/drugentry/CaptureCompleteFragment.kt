package com.example.myocr.drugentry

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    /** 当前正在语音输入的字段 key */
    private var voiceFieldKey: String = ""

    /** 语音输入结果回调 */
    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                onVoiceResult(voiceFieldKey, matches[0])
            }
        }
    }

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

        // 语音输入：每个字段的麦克风按钮
        binding.drugNameVoice.setOnClickListener { startVoiceInput("drugName") }
        binding.expiryVoice.setOnClickListener { startVoiceInput("expiryDate") }
        binding.manufacturerVoice.setOnClickListener { startVoiceInput("manufacturer") }
        binding.batchVoice.setOnClickListener { startVoiceInput("batchNumber") }

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

    /** 启动系统语音识别 */
    private fun startVoiceInput(fieldKey: String) {
        voiceFieldKey = fieldKey
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "请说出${getFieldLabel(fieldKey)}"
                )
            }
            voiceInputLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                R.string.voice_input_not_available,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 语音识别成功后的处理 */
    private fun onVoiceResult(fieldKey: String, spokenText: String) {
        val activity = requireActivity() as DrugEntryActivity
        val current = activity.session.drugInfo

        // 更新对应字段的值
        val updated = when (fieldKey) {
            "drugName" -> current.copy(drugName = spokenText)
            "expiryDate" -> current.copy(expiryDate = spokenText)
            "manufacturer" -> current.copy(manufacturer = spokenText)
            "batchNumber" -> current.copy(batchNumber = spokenText)
            else -> current
        }
        activity.updateDrugInfo(updated, FieldStatus.MANUAL)
        // 刷新页面
        updateFieldStatus(activity, activity.session)
        updateProgress(activity.session)
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
            captureButton = binding.drugNameCapture,
            cardView = binding.drugNameCard
        )

        updateSingleField(
            activity = activity,
            fieldKey = "expiryDate",
            fieldValue = info.expiryDate,
            valueView = binding.expiryValue,
            captureButton = binding.expiryCapture,
            cardView = binding.expiryCard
        )

        updateSingleField(
            activity = activity,
            fieldKey = "manufacturer",
            fieldValue = info.manufacturer,
            valueView = binding.manufacturerValue,
            captureButton = binding.manufacturerCapture,
            cardView = binding.manufacturerCard
        )

        updateSingleField(
            activity = activity,
            fieldKey = "batchNumber",
            fieldValue = info.batchNumber,
            valueView = binding.batchValue,
            captureButton = binding.batchCapture,
            cardView = binding.batchCard
        )
    }

    /**
     * 更新单个字段的显示状态
     *
     * 有值的字段显示值 + 品牌色背景，空字段显示橙色提示背景。
     * 如果 LLM 有多个候选，值可点击弹出选择器。
     * 拍照和语音按钮始终可见。
     */
    private fun updateSingleField(
        activity: DrugEntryActivity,
        fieldKey: String,
        fieldValue: String,
        valueView: View,
        captureButton: View,
        cardView: View
    ) {
        if (fieldValue.isNotBlank()) {
            (valueView as? android.widget.TextView)?.text = fieldValue
            valueView.visibility = View.VISIBLE
            cardView.setBackgroundResource(R.color.field_recognized_bg)

            // 如果 LLM 有多个候选，让值可点击切换
            val candidates = selectableCandidates[fieldKey]
            if (!candidates.isNullOrEmpty()) {
                valueView.isClickable = true
                valueView.setOnClickListener {
                    showCandidatePicker(activity, fieldKey, fieldValue, candidates)
                }
            } else {
                valueView.isClickable = false
                valueView.setOnClickListener(null)
            }
        } else {
            valueView.visibility = View.GONE
            cardView.setBackgroundResource(R.color.field_pending_bg)
            valueView.isClickable = false
            valueView.setOnClickListener(null)
        }
        // 拍照按钮和语音按钮始终可见
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
     * 显示 OCR 调试对话框：OCR 原文 + 语音输入 + LLM 候选
     */
    private fun showOcrDebugDialog(activity: DrugEntryActivity) {
        val session = activity.session
        val rawText = session.rawOcrText.ifBlank { "(无 OCR 原文)" }

        val sb = StringBuilder()
        sb.append("━━━ OCR 原始文本 ━━━\n")
        sb.append(rawText.take(800))
        if (rawText.length > 800) sb.append("\n…(截断)")
        sb.append("\n\n")

        if (session.voiceInputDrugName.isNotBlank()) {
            sb.append("━━━ 用户语音输入 ━━━\n")
            sb.append(session.voiceInputDrugName).append("\n\n")
        }

        // 传给 LLM 的完整输入
        if (session.llmFormattedInput.isNotBlank()) {
            sb.append("━━━ 发送给 LLM 的完整输入 ━━━\n")
            sb.append(session.llmFormattedInput.take(1000))
            if (session.llmFormattedInput.length > 1000) sb.append("\n…(截断)")
            sb.append("\n\n")
        }

        // 最终填到表单的值
        val finalInfo = session.drugInfo
        sb.append("━━━ 当前表单值 ━━━\n")
        sb.append("药品名称: ").append(finalInfo.drugName.ifBlank { "(空)" }).append("\n")
        sb.append("有效期:   ").append(finalInfo.expiryDate.ifBlank { "(空)" }).append("\n")
        sb.append("生产厂家: ").append(finalInfo.manufacturer.ifBlank { "(空)" }).append("\n")
        sb.append("批号:     ").append(finalInfo.batchNumber.ifBlank { "(空)" }).append("\n")
        sb.append("（正则解析已禁用，字段由 LLM 直接提取）\n")

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
