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
            valueView.isClickable = false
            valueView.setOnClickListener(null)
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
     * 显示 OCR 调试对话框：展示可直接复制的原始数据（OCR 原文、API 响应）
     */
    private fun showOcrDebugDialog(activity: DrugEntryActivity) {
        val session = activity.session
        val rawText = session.rawOcrText

        val sb = StringBuilder()

        if (rawText.isNotBlank()) {
            sb.append("━━━ OCR 原始文本 ━━━\n")
            sb.append(rawText)
        } else {
            sb.append("(无 OCR 原文)")
        }

        sb.append("\n\n(LLM 解析已禁用，无 LLM 响应)")

        val message = sb.toString()

        // 使用可滚动的对话框展示
        val scrollView = android.widget.ScrollView(requireContext())
        val textView = android.widget.TextView(requireContext()).apply {
            this.text = message
            textSize = 11f
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
