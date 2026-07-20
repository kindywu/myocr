package com.example.myocr.drugentry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
    private val binding
        get() = _binding!!

    /** 后台线程池（LLM API 调用用） */
    private val llmExecutor = Executors.newSingleThreadExecutor()

    /** DeepSeek LLM 客户端（未配置时 null） */
    private var deepSeekClient: DeepSeekClient? = null

    /** 当前正在语音输入的字段 key */
    private var voiceFieldKey: String = ""

    // ==================== 语音识别（SpeechRecognizer + 自定义动画对话框） ====================

    /** SpeechRecognizer 实例 */
    private var speechRecognizer: SpeechRecognizer? = null

    /** 语音输入动画对话框 */
    private var voiceDialog: androidx.appcompat.app.AlertDialog? = null

    /** 音波可视化条 */
    private val waveBars = mutableListOf<View>()

    /** 脉冲环动画 */
    private var pulseAnimator1: android.animation.Animator? = null
    private var pulseAnimator2: android.animation.Animator? = null

    /** 录音权限请求 */
    private val recordAudioPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    doStartVoiceInput(voiceFieldKey)
                } else {
                    Toast.makeText(
                                    requireContext(),
                                    R.string.voice_no_permission,
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }

    /** 创建 SpeechRecognizer 并设置监听器 */
    private fun createSpeechRecognizer(): SpeechRecognizer {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        // 对话框已显示，无需额外操作
                    }

                    override fun onBeginningOfSpeech() {
                        // 检测到用户开始说话
                    }

                    override fun onEndOfSpeech() {
                        // 用户停止说话
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        updateWaveBars(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches =
                                partialResults?.getStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION
                                )
                        if (!matches.isNullOrEmpty()) {
                            voiceDialog?.findViewById<TextView>(R.id.partialResult)?.text =
                                    matches[0]
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches =
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            onVoiceResult(voiceFieldKey, matches[0])
                        }
                        stopVoiceInput()
                    }

                    override fun onError(error: Int) {
                        val message =
                                when (error) {
                                    SpeechRecognizer.ERROR_NO_MATCH -> "未听清，请重试"
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有说话，已超时"
                                    SpeechRecognizer.ERROR_AUDIO -> "录音失败"
                                    SpeechRecognizer.ERROR_CLIENT -> "语音识别服务异常"
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                                    SpeechRecognizer.ERROR_NETWORK -> "网络不可用"
                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                                    SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                                    else -> "识别出错 ($error)"
                                }
                        if (isAdded) {
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        }
                        stopVoiceInput()
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
        )
        return recognizer
    }

    /** 显示语音输入动画对话框 */
    private fun showVoiceDialog(fieldKey: String) {
        val dialogView =
                LayoutInflater.from(requireContext()).inflate(R.layout.dialog_voice_input, null)

        val fieldLabel = dialogView.findViewById<TextView>(R.id.fieldLabel)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val closeButton = dialogView.findViewById<android.widget.ImageButton>(R.id.closeButton)
        val pulseRing1 = dialogView.findViewById<View>(R.id.pulseRing1)
        val pulseRing2 = dialogView.findViewById<View>(R.id.pulseRing2)

        fieldLabel.text = "正在听「${getFieldLabel(fieldKey)}」"

        // 收集音波条
        waveBars.clear()
        waveBars.addAll(
                listOf(
                        dialogView.findViewById(R.id.waveBar1),
                        dialogView.findViewById(R.id.waveBar2),
                        dialogView.findViewById(R.id.waveBar3),
                        dialogView.findViewById(R.id.waveBar4),
                        dialogView.findViewById(R.id.waveBar5),
                )
        )

        // 启动脉冲环动画
        pulseAnimator1 = startPulseAnimation(pulseRing1, 1200, 1.0f, 1.8f)
        pulseAnimator2 = startPulseAnimation(pulseRing2, 1200, 1.0f, 1.4f)

        cancelButton.setOnClickListener { stopVoiceInput() }
        closeButton.setOnClickListener { stopVoiceInput() }

        voiceDialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setView(dialogView)
                        .setCancelable(false)
                        .show()
    }

    /** 脉冲环缩放 + 淡出动画 */
    private fun startPulseAnimation(
            view: View,
            duration: Long,
            fromScale: Float,
            toScale: Float
    ): android.animation.Animator {
        val scaleX =
                android.animation.ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, toScale)
                        .apply {
                            this.duration = duration
                            interpolator = AccelerateDecelerateInterpolator()
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            repeatMode = android.animation.ValueAnimator.RESTART
                        }
        val scaleY =
                android.animation.ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, toScale)
                        .apply {
                            this.duration = duration
                            interpolator = AccelerateDecelerateInterpolator()
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            repeatMode = android.animation.ValueAnimator.RESTART
                        }
        val alpha =
                android.animation.ObjectAnimator.ofFloat(view, View.ALPHA, 0.6f, 0.0f).apply {
                    this.duration = duration
                    interpolator = AccelerateDecelerateInterpolator()
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.RESTART
                }
        val set = android.animation.AnimatorSet().apply { playTogether(scaleX, scaleY, alpha) }
        set.start()
        return set
    }

    /** 根据音量更新音波条高度 */
    private fun updateWaveBars(rmsdB: Float) {
        // rmsdB 范围约 0-10，归一化
        val normalized = (rmsdB / 12.0f).coerceIn(0f, 1f)
        for ((i, bar) in waveBars.withIndex()) {
            // 每根条略有偏移，形成流动感
            val offset = ((i - 2) * 0.12f)
            val scale = 0.3f + (normalized + offset).coerceIn(0f, 1f) * 1.7f
            bar.scaleY = scale
        }
    }

    /** 停止语音输入 */
    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        dismissVoiceDialog()
    }

    /** 关闭语音对话框并清理动画 */
    private fun dismissVoiceDialog() {
        pulseAnimator1?.cancel()
        pulseAnimator2?.cancel()
        voiceDialog?.dismiss()
        voiceDialog = null
        // 重置音波条
        for (bar in waveBars) {
            bar.scaleY = 1.0f
        }
    }

    // ==================== 语音识别结束 ====================

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding =
                com.example.myocr.databinding.FragmentCaptureCompleteBinding.inflate(
                        inflater,
                        container,
                        false
                )
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

        // 语音输入：每个字段的麦克风按钮 → 改用 SpeechRecognizer + 自定义对话框
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
        binding.debugRawOcr.visibility =
                if (session.rawOcrText.isNotBlank()) View.VISIBLE else View.GONE
    }

    /** 启动系统语音识别 —— 自动请求权限后使用 SpeechRecognizer + 自定义动画 */
    private fun startVoiceInput(fieldKey: String) {
        voiceFieldKey = fieldKey
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        doStartVoiceInput(fieldKey)
    }

    /** 已有权限，执行语音输入 */
    private fun doStartVoiceInput(fieldKey: String) {
        voiceFieldKey = fieldKey

        // 创建 SpeechRecognizer（如果尚未创建或已被销毁）
        if (speechRecognizer == null) {
            speechRecognizer = createSpeechRecognizer()
        }

        // 显示自定义动画对话框
        showVoiceDialog(fieldKey)

        // 启动识别
        try {
            val intent =
                    Intent().apply {
                        putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer start failed", e)
            Toast.makeText(requireContext(), "语音识别启动失败", Toast.LENGTH_SHORT).show()
            dismissVoiceDialog()
        }
    }

    /** 语音识别成功后的处理 */
    private fun onVoiceResult(fieldKey: String, spokenText: String) {
        val activity = requireActivity() as DrugEntryActivity
        val current = activity.session.drugInfo

        // 获取当前字段值
        val currentValue: String =
                when (fieldKey) {
                    "drugName" -> current.drugName
                    "expiryDate" -> current.expiryDate
                    "manufacturer" -> current.manufacturer
                    "batchNumber" -> current.batchNumber
                    else -> ""
                }

        // 情况 1：当前字段为空 → 直接填入语音结果
        if (currentValue.isBlank()) {
            applyVoiceResult(activity, fieldKey, spokenText)
            return
        }

        // 情况 2：语音结果与当前值完全相同 → 无需操作
        if (spokenText == currentValue) {
            Toast.makeText(requireContext(), "语音识别结果与 OCR 一致", Toast.LENGTH_SHORT).show()
            return
        }

        // 情况 3：有差异 → 弹出确认对话框让用户选择
        showVoiceConfirmDialog(activity, fieldKey, currentValue, spokenText)
    }

    /** 直接应用语音结果到字段 */
    private fun applyVoiceResult(activity: DrugEntryActivity, fieldKey: String, value: String) {
        val current = activity.session.drugInfo
        val updated =
                when (fieldKey) {
                    "drugName" -> current.copy(drugName = value)
                    "expiryDate" -> current.copy(expiryDate = value)
                    "manufacturer" -> current.copy(manufacturer = value)
                    "batchNumber" -> current.copy(batchNumber = value)
                    else -> current
                }
        activity.updateDrugInfo(updated, FieldStatus.MANUAL)
        updateFieldStatus(activity, activity.session)
        updateProgress(activity.session)
    }

    /** 显示语音结果 vs OCR 值对比确认对话框 */
    private fun showVoiceConfirmDialog(
            activity: DrugEntryActivity,
            fieldKey: String,
            currentValue: String,
            spokenText: String
    ) {
        val dialogView =
                LayoutInflater.from(requireContext()).inflate(R.layout.dialog_voice_confirm, null)

        dialogView.findViewById<TextView>(R.id.currentValue).text = currentValue
        dialogView.findViewById<TextView>(R.id.voiceValue).text = spokenText

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("语音识别结果 — ${getFieldLabel(fieldKey)}")
                .setView(dialogView)
                .setPositiveButton("替换") { _, _ ->
                    applyVoiceResult(activity, fieldKey, spokenText)
                }
                .setNegativeButton("保留") { _, _ ->
                    // 什么都不做，保留原值
                }
                .setCancelable(true)
                .show()
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
     * 有值的字段显示值 + 品牌色背景，空字段隐藏值区域显示橙色提示。 字段值可点击触发单字段 LLM 重新提取（改善结果）。 拍照和语音按钮始终可见。
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
     * 用户在补全页点击字段值时触发，对该字段单独调用一次 DeepSeek API， 提取结果只更新当前字段，不影响其他字段已有值。
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
                val voiceText = activity.session.fieldVoiceInputs[fieldKey] ?: ""
                val ocrLines = activity.session.ocrLines
                val singleResult = client.extractSingleField(fieldKey, rawText, ocrLines, voiceText)
                val newValue = singleResult.allCandidates[fieldKey]?.bestValue ?: ""

                // 保存单字段请求/响应到 session（供调试页展示），后续 UI 更新也在同一 runOnUiThread 中
                activity.runOnUiThread {
                    // 先保存 LLM 数据到 session
                    val fullRequest = buildString {
                        append("=== System Prompt (${getFieldLabel(fieldKey)}) ===\n")
                        append(singleResult.systemPrompt)
                        append("\n\n=== User Message ===\n")
                        append(singleResult.formattedInput)
                    }
                    activity.updateSession {
                        it.copy(
                            llmRequestText = fullRequest,
                            llmResponseJson = singleResult.rawApiResponse
                        )
                    }

                    if (!isAdded) return@runOnUiThread

                    if (newValue.isNotBlank()) {
                        // 只更新该字段
                        val current = activity.session.drugInfo
                        val updated =
                                when (fieldKey) {
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

                        Toast.makeText(
                                        requireContext(),
                                        "${getFieldLabel(fieldKey)}: $newValue",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    } else {
                        (valueView as? android.widget.TextView)?.text =
                                activity.session.drugInfo.run {
                                    when (fieldKey) {
                                        "drugName" -> drugName
                                        "expiryDate" -> expiryDate
                                        "manufacturer" -> manufacturer
                                        "batchNumber" -> batchNumber
                                        else -> ""
                                    }
                                }
                        valueView.isClickable = deepSeekClient != null
                        Toast.makeText(requireContext(), "LLM 无法提取该字段", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Single-field extraction [$fieldKey] failed", e)
                activity.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    (valueView as? android.widget.TextView)?.text =
                            activity.session.drugInfo.run {
                                when (fieldKey) {
                                    "drugName" -> drugName
                                    "expiryDate" -> expiryDate
                                    "manufacturer" -> manufacturer
                                    "batchNumber" -> batchNumber
                                    else -> ""
                                }
                            }
                    valueView.isClickable = deepSeekClient != null
                    Toast.makeText(
                                    requireContext(),
                                    "提取失败: ${e.message?.take(50)}",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }
        }
    }

    /** 获取字段的中文标签 */
    private fun getFieldLabel(fieldKey: String): String {
        return when (fieldKey) {
            "drugName" -> "药品名称"
            "expiryDate" -> "有效期至"
            "manufacturer" -> "生产厂家"
            "batchNumber" -> "批号"
            else -> fieldKey
        }
    }

    /** 显示 OCR 调试对话框：展示可直接复制的原始数据（OCR 原文、语音输入、API 响应） */
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

        val voiceInputs = session.fieldVoiceInputs
        if (voiceInputs.isNotEmpty()) {
            sb.append("\n\n━━━ 语音补充输入 ━━━\n")
            for ((fieldKey, text) in voiceInputs) {
                val label = when (fieldKey) {
                    "_global" -> "全局补充"
                    else -> getFieldLabel(fieldKey)
                }
                sb.append("[$label] $text\n")
            }
        }

        val llmJson = session.llmResponseJson
        val llmRequest = session.llmRequestText

        if (llmRequest.isNotBlank()) {
            sb.append("\n\n━━━ LLM 请求 ━━━\n")
            sb.append(llmRequest)
        }

        if (llmJson.isNotBlank()) {
            sb.append("\n\n━━━ LLM 结果响应 ━━━\n")
            sb.append(llmJson)
        } else {
            sb.append("\n\n(LLM 未执行或无响应)")
        }

        val message = sb.toString()

        // 使用可滚动的对话框展示
        val scrollView = android.widget.ScrollView(requireContext())
        val textView =
                android.widget.TextView(requireContext()).apply {
                    this.text = message
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setLineSpacing(4f, 1f)
                    setPadding(24, 16, 24, 16)
                    setTextIsSelectable(true) // 允许长按复制
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
        // 清理语音识别资源
        stopVoiceInput()
        speechRecognizer?.destroy()
        speechRecognizer = null
        llmExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CaptureCompleteFragment"
    }
}
