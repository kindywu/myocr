package com.example.myocr.drugentry

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.myocr.R
import java.util.concurrent.Executors

/**
 * 多角度补全进度页
 *
 * 展示各字段的识别状态，支持：
 * - 查看已识别字段的值
 * - 点击字段值触发单字段 LLM 提取（对空字段或想改善的字段）
 * - 对任意字段单独补拍（字段级采集，不影响其他字段）
 * - 继续拍摄更多角度（全字段采集）
 * - 查看结果进入人工确认页
 */
class CaptureCompleteFragment : Fragment() {

    private var _binding: com.example.myocr.databinding.FragmentCaptureCompleteBinding? = null
    private val binding get() = _binding!!

    /** 后台线程池（LLM API 调用用） */
    private val llmExecutor = Executors.newSingleThreadExecutor()

    /** DeepSeek LLM 客户端（未配置时 null） */
    private var deepSeekClient: DeepSeekClient? = null

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

        // 初始化 DeepSeekClient
        deepSeekClient = DeepSeekClient.create(requireContext())

        // 返回
        binding.backButton.setOnClickListener { activity.supportFragmentManager.popBackStack() }

        // 更新进度和字段状态
        updateProgress(session)
        updateFieldStatus(activity, session)

        // 字段值点击 → 单字段 LLM 提取
        binding.drugNameValue.setOnClickListener {
            startSingleFieldExtraction(activity, "drugName", binding.drugNameValue)
        }
        binding.expiryValue.setOnClickListener {
            startSingleFieldExtraction(activity, "expiryDate", binding.expiryValue)
        }
        binding.manufacturerValue.setOnClickListener {
            startSingleFieldExtraction(activity, "manufacturer", binding.manufacturerValue)
        }
        binding.batchValue.setOnClickListener {
            startSingleFieldExtraction(activity, "batchNumber", binding.batchValue)
        }

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

        // 调试：查看原始 OCR 文本和 LLM 响应
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
     * 有值的字段显示值 + 品牌色背景，空字段隐藏值区域显示橙色提示。
     * 字段值可点击触发单字段 LLM 重新提取（改善结果）。
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
            // 可点击触发单字段 LLM 重新提取
            valueView.isClickable = deepSeekClient != null
        } else {
            valueView.visibility = View.GONE
            cardView.setBackgroundResource(R.color.field_pending_bg)
        }
        captureButton.visibility = View.VISIBLE
    }

    // ==================== 单字段 LLM 提取 ====================

    /**
     * 启动单字段 LLM 提取
     *
     * 用户在补全页点击字段值时触发，对该字段单独调用一次 DeepSeek API，
     * 提取结果只更新当前字段，不影响其他字段已有值。
     */
    private fun startSingleFieldExtraction(
        activity: DrugEntryActivity,
        fieldKey: String,
        valueView: View
    ) {
        val client = deepSeekClient ?: return
        val rawText = activity.session.rawOcrText
        if (rawText.isBlank()) {
            Toast.makeText(requireContext(), "无 OCR 文本，无法提取", Toast.LENGTH_SHORT).show()
            return
        }

        valueView.isClickable = false
        (valueView as? android.widget.TextView)?.text = "提取中…"

        llmExecutor.execute {
            try {
                val fc = client.extractSingleField(fieldKey, rawText)
                val newValue = fc.bestValue

                activity.runOnUiThread {
                    if (!isAdded) return@runOnUiThread

                    if (newValue.isNotBlank()) {
                        // 只更新该字段
                        val current = activity.session.drugInfo
                        val updated = when (fieldKey) {
                            "drugName" -> current.copy(drugName = newValue)
                            "expiryDate" -> current.copy(expiryDate = newValue)
                            "manufacturer" -> current.copy(manufacturer = newValue)
                            "batchNumber" -> current.copy(batchNumber = newValue)
                            else -> return@runOnUiThread
                        }
                        activity.updateDrugInfo(updated, FieldStatus.RECOGNIZED)
                        // 刷新页面
                        updateFieldStatus(activity, activity.session)
                        updateProgress(activity.session)

                        Toast.makeText(requireContext(),
                            "${getFieldLabel(fieldKey)}: $newValue", Toast.LENGTH_SHORT).show()
                    } else {
                        (valueView as? android.widget.TextView)?.text = activity.session.drugInfo.run {
                            when (fieldKey) {
                                "drugName" -> drugName
                                "expiryDate" -> expiryDate
                                "manufacturer" -> manufacturer
                                "batchNumber" -> batchNumber
                                else -> ""
                            }
                        }
                        valueView.isClickable = deepSeekClient != null
                        Toast.makeText(requireContext(),
                            "LLM 无法提取该字段", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Single-field extraction [$fieldKey] failed", e)
                activity.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    (valueView as? android.widget.TextView)?.text = activity.session.drugInfo.run {
                        when (fieldKey) {
                            "drugName" -> drugName
                            "expiryDate" -> expiryDate
                            "manufacturer" -> manufacturer
                            "batchNumber" -> batchNumber
                            else -> ""
                        }
                    }
                    valueView.isClickable = deepSeekClient != null
                    Toast.makeText(requireContext(),
                        "提取失败: ${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

        val llmJson = session.llmResponseJson
        if (llmJson.isNotBlank()) {
            sb.append("\n\n━━━ LLM 提取结果 ━━━\n")
            sb.append(llmJson.take(3000))
        } else {
            sb.append("\n\n(LLM 未执行或无响应)")
        }

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

    override fun onDestroy() {
        super.onDestroy()
        llmExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CaptureCompleteFragment"
    }
}
