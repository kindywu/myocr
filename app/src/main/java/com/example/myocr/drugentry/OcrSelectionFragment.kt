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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.myocr.OcrLine
import com.example.myocr.R
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * OCR 行选择页
 *
 * 用户在裁剪页完成 OCR 后进入此页，从 OCR 识别的文字行中选择哪些行对应哪个字段。
 * 提供三种出口：
 * - 手动选择行并分配字段 → 确认
 * - 跳过 → 使用规则提取（quickExtract）
 * - 用 AI → 调用 DeepSeek 提取全部字段
 */
class OcrSelectionFragment : Fragment() {

    companion object {
        private const val TAG = "OcrSelectionFragment"

        private val FIELD_KEYS = listOf("drugName", "expiryDate", "manufacturer", "batchNumber")
        private val FIELD_LABELS = mapOf(
            "drugName" to "药品名称",
            "expiryDate" to "有效期至",
            "manufacturer" to "生产厂家",
            "batchNumber" to "批号"
        )
        private val FIELD_ICONS = mapOf(
            "drugName" to "💊",
            "expiryDate" to "📅",
            "manufacturer" to "🏭",
            "batchNumber" to "🔢"
        )
    }

    private var _binding: com.example.myocr.databinding.FragmentOcrSelectionBinding? = null
    private val binding get() = _binding!!

    /** OCR 行列表（直接从 session 读取） */
    private var ocrLines: List<OcrLine> = emptyList()

    /** 字段 → 行索引的映射 */
    private val fieldAssignments = mutableMapOf<String, Int>()

    /** 反向映射：行索引 → 已分配的字段名 */
    private val lineToField = mutableMapOf<Int, String>()

    /** 字段 → 用户编辑后的文字（非空时覆盖 OCR 原文字） */
    private val fieldOverrideTexts = mutableMapOf<String, String>()

    /** 当前选择底页对应的行索引 */
    private var pendingLineIndex: Int = -1

    /** 底页对话框 */
    private var bottomSheet: BottomSheetDialog? = null

    // ==================== 语音输入 ====================

    private var speechRecognizer: SpeechRecognizer? = null
    /** 语音输入期间临时对话框 */
    private var voiceListeningDialog: androidx.appcompat.app.AlertDialog? = null
    /** 语音补充确认后的回调，接收语音文本（空串表示放弃） */
    private var onVoiceCompleted: ((String) -> Unit)? = null

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            doStartVoiceInput()
        } else {
            Toast.makeText(requireContext(), R.string.voice_no_permission, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createSpeechRecognizer(): SpeechRecognizer {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceListeningDialog?.findViewById<TextView>(R.id.partialResult)?.text = matches[0]
                }
            }

            override fun onResults(results: Bundle?) {
                stopVoiceInput()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    showVoiceConfirmDialog(matches[0])
                } else {
                    Toast.makeText(requireContext(), "未听清，请重试", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(error: Int) {
                stopVoiceInput()
                val message = when (error) {
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
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return recognizer
    }

    /** 启动语音输入（需先有权限），收到结果后回调 callback */
    private fun startVoiceInput(callback: (String) -> Unit) {
        onVoiceCompleted = callback
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        doStartVoiceInput()
    }

    private fun doStartVoiceInput() {
        if (speechRecognizer == null) {
            speechRecognizer = createSpeechRecognizer()
        }

        showVoiceListeningDialog()

        try {
            val intent = Intent().apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer start failed", e)
            Toast.makeText(requireContext(), "语音识别启动失败", Toast.LENGTH_SHORT).show()
            dismissVoiceListeningDialog()
        }
    }

    /** 显示"正在聆听…"对话框 */
    private fun showVoiceListeningDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_voice_input, null)

        // 隐藏不必要的动画元素（纯文字模式）
        dialogView.findViewById<View>(R.id.pulseRing1)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.pulseRing2)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.waveContainer)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.micContainer)?.visibility = View.GONE

        // 绑定关闭/取消按钮
        dialogView.findViewById<View>(R.id.cancelButton)?.setOnClickListener { stopVoiceInput() }
        dialogView.findViewById<View>(R.id.closeButton)?.setOnClickListener { stopVoiceInput() }

        voiceListeningDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .show()
    }

    private fun dismissVoiceListeningDialog() {
        voiceListeningDialog?.dismiss()
        voiceListeningDialog = null
    }

    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        dismissVoiceListeningDialog()
    }

    /** 语音识别结果确认对话框 → "采纳"/"放弃" */
    private fun showVoiceConfirmDialog(spokenText: String) {
        if (!isAdded) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音识别结果")
            .setMessage(spokenText)
            .setPositiveButton("采纳") { _, _ ->
                onVoiceCompleted?.invoke(spokenText)
                onVoiceCompleted = null
            }
            .setNegativeButton("放弃") { _, _ ->
                onVoiceCompleted?.invoke("")
                onVoiceCompleted = null
            }
            .setCancelable(false)
            .show()
    }

    // ==================== 生命周期 ====================

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = com.example.myocr.databinding.FragmentOcrSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as DrugEntryActivity

        ocrLines = activity.session.ocrLines

        // 返回
        binding.backButton.setOnClickListener { activity.supportFragmentManager.popBackStack() }

        // 跳过 → 规则提取
        binding.skipButton.setOnClickListener {
            val drugInfo = DrugOcrParser.quickExtract(ocrLines)
            Log.d(TAG, "Skip to quickExtract: name=[${drugInfo.drugName}]")
            activity.updateDrugInfo(drugInfo, FieldStatus.RECOGNIZED)
            activity.clearRetake()
            activity.navigateTo(DrugEntryStep.COMPLETION)
        }

        // 确认 → 用选中的行构建 DrugInfo
        binding.confirmButton.setOnClickListener {
            val drugInfo = buildDrugInfoFromSelection()
            Log.d(TAG, "Confirm selection: name=[${drugInfo.drugName}]")
            activity.updateDrugInfo(drugInfo, FieldStatus.RECOGNIZED)
            activity.clearRetake()
            activity.navigateTo(DrugEntryStep.COMPLETION)
        }

        // AI 提取（顶部大卡片）
        binding.aiAutoFillCard.setOnClickListener {
            onAiExtractClicked(activity)
        }

        // 渲染 OCR 行
        renderOcrLines()
        // 渲染字段映射
        renderFieldMapping()
        // 自动预分配
        autoAssignFields()
        updateConfirmButton()
    }

    // ==================== OCR 行渲染 ====================

    /**
     * 将 OCR 行列表渲染为可点击的卡片
     */
    private fun renderOcrLines() {
        val container = binding.ocrLineContainer
        container.removeAllViews()

        for ((index, line) in ocrLines.withIndex()) {
            val text = line.text.trim()
            if (text.isBlank()) continue

            val card = createOcrLineCard(index, text, line.confidence)
            container.addView(card)
        }
    }

    /**
     * 创建单行 OCR 卡片视图
     */
    private fun createOcrLineCard(index: Int, text: String, confidence: Float): View {
        val activity = requireActivity()

        // 使用内置的简单布局：LinearLayout 包裹文字+标签
        val card = com.google.android.material.card.MaterialCardView(activity)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 }

        card.cardElevation = 0f
        card.radius = 12f
        card.setContentPadding(16, 14, 16, 14)
        card.isClickable = true
        card.isFocusable = true
        card.tag = index

        // 水平布局：选中指示器 + 文字信息 + 字段标签
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // 选中勾
        val check = TextView(activity)
        check.layoutParams = LinearLayout.LayoutParams(28, 28).apply { marginEnd = 12 }
        check.gravity = android.view.Gravity.CENTER
        check.textSize = 14f
        check.text = "✓"
        check.setBackgroundResource(R.drawable.circle_brand_bg)
        check.setTextColor(0xFFCCCCCC.toInt())
        check.visibility = if (lineToField.containsKey(index)) android.view.View.VISIBLE else android.view.View.GONE
        row.addView(check)

        // 文字信息
        val infoCol = LinearLayout(activity)
        infoCol.orientation = LinearLayout.VERTICAL
        infoCol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val lineText = TextView(activity)
        lineText.textSize = 15f
        lineText.setTextColor(0xFF222222.toInt())
        lineText.text = text
        infoCol.addView(lineText)

        val lineMeta = TextView(activity)
        lineMeta.textSize = 11f
        lineMeta.setTextColor(0xFFAAAAAA.toInt())
        lineMeta.text = "行 ${index + 1} · 置信度 ${"%.0f".format(confidence * 100)}%"
        infoCol.addView(lineMeta)

        row.addView(infoCol)

        // 字段标签
        val tag = TextView(activity)
        tag.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 8 }
        tag.textSize = 11f
        tag.setPadding(10, 3, 10, 3)

        val assignedField = lineToField[index]
        if (assignedField != null) {
            tag.text = FIELD_LABELS[assignedField] ?: ""
            tag.setTextColor(0xFF4A90D9.toInt())
            tag.setBackgroundColor(0xFFEEF4FF.toInt())
            card.setCardBackgroundColor(0xFFF5F9FF.toInt())
            card.strokeColor = 0xFF4A90D9.toInt()
            card.strokeWidth = 2
        } else {
            tag.visibility = android.view.View.GONE
        }
        row.addView(tag)

        card.addView(row)

        // 点击 → 打开底页
        card.setOnClickListener {
            pendingLineIndex = index
            showFieldPicker(index, text)
        }

        return card
    }

    /**
     * 刷新所有 OCR 卡片（分配状态变化后调用）
     */
    private fun refreshOcrCards() {
        renderOcrLines()
    }

    // ==================== 字段底页 ====================

    /**
     * 显示字段选择底页
     */
    private fun showFieldPicker(lineIndex: Int, text: String) {
        val activity = requireActivity()
        val dialog = BottomSheetDialog(activity)
        val sheetView = LayoutInflater.from(activity).inflate(
            R.layout.bottom_sheet_field_picker, null
        )

        // 设置选定文字（可编辑）
        val textInput = sheetView.findViewById<android.widget.EditText>(R.id.sheetSelectedText)
        textInput.setText(text)
        textInput.setSelection(text.length)

        // 当前已分配的字段
        val currentField = lineToField[lineIndex]

        // 设置字段选项点击
        val fieldOptionIds = mapOf(
            "drugName" to R.id.sheetFieldDrugName,
            "expiryDate" to R.id.sheetFieldExpiry,
            "manufacturer" to R.id.sheetFieldManufacturer,
            "batchNumber" to R.id.sheetFieldBatch
        )
        val fieldCheckIds = mapOf(
            "drugName" to R.id.sheetFieldDrugNameCheck,
            "expiryDate" to R.id.sheetFieldExpiryCheck,
            "manufacturer" to R.id.sheetFieldManufacturerCheck,
            "batchNumber" to R.id.sheetFieldBatchCheck
        )

        // 高亮当前已分配的字段
        for ((fieldKey, viewId) in fieldOptionIds) {
            val option = sheetView.findViewById<View>(viewId)
            val check = sheetView.findViewById<TextView>(fieldCheckIds[fieldKey]!!)
            if (fieldKey == currentField) {
                option.setBackgroundColor(0xFFEEF4FF.toInt())
                check?.visibility = View.VISIBLE
            } else {
                option.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                check?.visibility = View.GONE
            }
            option.setOnClickListener {
                val editedText = sheetView.findViewById<android.widget.EditText>(R.id.sheetSelectedText).text.toString().trim()
                assignField(fieldKey, lineIndex)
                // 保存编辑后的文字（与原文不同时才记录）
                if (editedText != text && editedText.isNotBlank()) {
                    fieldOverrideTexts[fieldKey] = editedText
                } else {
                    fieldOverrideTexts.remove(fieldKey)
                }
                dialog.dismiss()
            }
        }

        // 清除分配按钮
        val unassignBtn = sheetView.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.sheetUnassignButton
        )
        if (currentField != null) {
            unassignBtn.visibility = View.VISIBLE
            unassignBtn.setOnClickListener {
                unassignField(lineIndex)
                dialog.dismiss()
            }
        } else {
            unassignBtn.visibility = View.GONE
        }

        // 确定按钮
        sheetView.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.sheetConfirmButton
        ).setOnClickListener { dialog.dismiss() }

        dialog.setContentView(sheetView)
        bottomSheet = dialog
        dialog.show()
    }

    /**
     * 将指定行分配给指定字段
     */
    private fun assignField(fieldKey: String, lineIndex: Int) {
        // 如果该字段已有其他行，先清除
        val existingLine = fieldAssignments[fieldKey]
        if (existingLine != null) {
            lineToField.remove(existingLine)
            fieldOverrideTexts.remove(fieldKey)
        }

        // 如果该行已有其他字段，先清除
        val existingField = lineToField[lineIndex]
        if (existingField != null) {
            fieldAssignments.remove(existingField)
            fieldOverrideTexts.remove(existingField)
        }

        // 分配
        fieldAssignments[fieldKey] = lineIndex
        lineToField[lineIndex] = fieldKey

        Log.d(TAG, "Assigned line $lineIndex to $fieldKey")

        refreshOcrCards()
        renderFieldMapping()
        updateConfirmButton()
    }

    /**
     * 清除指定行的分配
     */
    private fun unassignField(lineIndex: Int) {
        val field = lineToField[lineIndex] ?: return
        fieldAssignments.remove(field)
        lineToField.remove(lineIndex)
        fieldOverrideTexts.remove(field)

        Log.d(TAG, "Unassigned line $lineIndex from $field")

        refreshOcrCards()
        renderFieldMapping()
        updateConfirmButton()
    }

    // ==================== 字段映射UI ====================

    /**
     * 渲染字段映射区（4个字段卡片）
     */
    private fun renderFieldMapping() {
        for (fieldKey in FIELD_KEYS) {
            updateFieldCard(fieldKey)
        }
    }

    /**
     * 更新单个字段卡片
     */
    private fun updateFieldCard(fieldKey: String) {
        val valueViewId = when (fieldKey) {
            "drugName" -> R.id.fieldDrugNameValue
            "expiryDate" -> R.id.fieldExpiryValue
            "manufacturer" -> R.id.fieldManufacturerValue
            "batchNumber" -> R.id.fieldBatchValue
            else -> return
        }
        val clearBtnId = when (fieldKey) {
            "drugName" -> R.id.fieldDrugNameClear
            "expiryDate" -> R.id.fieldExpiryClear
            "manufacturer" -> R.id.fieldManufacturerClear
            "batchNumber" -> R.id.fieldBatchClear
            else -> return
        }
        val cardId = when (fieldKey) {
            "drugName" -> R.id.fieldDrugNameCard
            "expiryDate" -> R.id.fieldExpiryCard
            "manufacturer" -> R.id.fieldManufacturerCard
            "batchNumber" -> R.id.fieldBatchCard
            else -> return
        }
        val clickTargetId = when (fieldKey) {
            "drugName" -> R.id.fieldDrugName
            "expiryDate" -> R.id.fieldExpiry
            "manufacturer" -> R.id.fieldManufacturer
            "batchNumber" -> R.id.fieldBatch
            else -> return
        }

        val valueView = binding.root.findViewById<TextView>(valueViewId) ?: return
        val clearBtn = binding.root.findViewById<View>(clearBtnId) ?: return
        val card = binding.root.findViewById<com.google.android.material.card.MaterialCardView>(cardId) ?: return
        val clickTarget = binding.root.findViewById<View>(clickTargetId) ?: return

        val lineIndex = fieldAssignments[fieldKey]

        if (lineIndex != null && lineIndex in ocrLines.indices) {
            val line = ocrLines[lineIndex]
            val displayText = fieldOverrideTexts[fieldKey] ?: line.text
            valueView.text = displayText
            valueView.setTextColor(0xFF222222.toInt())
            valueView.setTypeface(null, android.graphics.Typeface.BOLD)
            card.strokeColor = 0xFF4A90D9.toInt()
            card.strokeWidth = 2
            clearBtn.visibility = View.VISIBLE
            clearBtn.setOnClickListener { unassignField(lineIndex) }
        } else {
            valueView.text = "点击上方文字行分配…"
            valueView.setTextColor(0xFFAAAAAA.toInt())
            valueView.setTypeface(null, android.graphics.Typeface.ITALIC)
            card.strokeColor = 0xFFDDDDDD.toInt()
            card.strokeWidth = 1
            clearBtn.visibility = View.GONE
        }

        // 点击字段卡片 → 对应行打开底页
        clickTarget.setOnClickListener {
            val idx = fieldAssignments[fieldKey]
            if (idx != null && idx in ocrLines.indices) {
                pendingLineIndex = idx
                showFieldPicker(idx, ocrLines[idx].text)
            }
        }
    }

    // ==================== 自动预分配 ====================

    /**
     * 自动分配：按规则尝试为各字段匹配对应行
     */
    private fun autoAssignFields() {
        for ((index, line) in ocrLines.withIndex()) {
            val text = line.text.trim()
            if (text.isBlank()) continue

            // 药品名称：匹配剂型后缀
            if (fieldAssignments["drugName"] == null &&
                DrugOcrParser.VALID_DRUG_SUFFIXES.any { text.contains(it) }
            ) {
                assignField("drugName", index)
                continue
            }

            // 有效期：关键词 + 日期模式
            if (fieldAssignments["expiryDate"] == null &&
                (text.contains("有效期") || text.contains("EXP", ignoreCase = true) ||
                 text.contains("有效至") || text.contains("失效") ||
                 text.matches(Regex("""^\d{4}[-/.]\d{1,2}([-/.]\d{1,2})?$""")))
            ) {
                assignField("expiryDate", index)
                continue
            }

            // 厂家
            if (fieldAssignments["manufacturer"] == null &&
                (text.contains("生产") || text.contains("制药") ||
                 text.contains("药业") || text.contains("有限公司"))
            ) {
                assignField("manufacturer", index)
                continue
            }

            // 批号
            if (fieldAssignments["batchNumber"] == null &&
                (text.contains("批号") || text.contains("Lot", ignoreCase = true) ||
                 text.contains("BATCH", ignoreCase = true))
            ) {
                assignField("batchNumber", index)
                continue
            }
        }

        // 如果没有匹配到药品名称，取第一个含中文的行
        if (fieldAssignments["drugName"] == null) {
            val firstChinese = ocrLines.indexOfFirst { line ->
                line.text.any { it in '一'..'鿿' }
            }
            if (firstChinese >= 0) {
                assignField("drugName", firstChinese)
            }
        }
    }

    // ==================== 构建 DrugInfo ====================

    /**
     * 从用户选择构建 DrugInfo
     */
    private fun buildDrugInfoFromSelection(): DrugInfo {
        return DrugInfo(
            drugName = getAssignedText("drugName"),
            expiryDate = getAssignedText("expiryDate"),
            manufacturer = getAssignedText("manufacturer"),
            batchNumber = getAssignedText("batchNumber")
        )
    }

    private fun getAssignedText(fieldKey: String): String {
        // 优先使用用户编辑后的文字
        fieldOverrideTexts[fieldKey]?.let { if (it.isNotBlank()) return it }
        val idx = fieldAssignments[fieldKey] ?: return ""
        if (idx in ocrLines.indices) return ocrLines[idx].text.trim()
        return ""
    }

    // ==================== UI 更新 ====================

    /**
     * 根据分配数量更新确认按钮状态
     */
    private fun updateConfirmButton() {
        val filled = fieldAssignments.count { it.value >= 0 }
        binding.confirmButton.isEnabled = filled > 0
        binding.confirmButton.text = "确认并继续 ($filled/${FIELD_KEYS.size})"

        val summaryBar = binding.summaryBar
        if (filled > 0) {
            summaryBar.visibility = View.VISIBLE
            binding.summaryText.text = "✅ 已分配 $filled/${FIELD_KEYS.size} 个字段"
        } else {
            summaryBar.visibility = View.GONE
        }
    }

    // ==================== AI 提取 ====================

    /**
     * 用 AI 提取全部字段
     *
     * 先询问是否添加语音补充，然后进行 LLM 提取。
     */
    private fun onAiExtractClicked(activity: DrugEntryActivity) {
        val rawText = activity.session.rawOcrText
        if (rawText.isBlank()) {
            Toast.makeText(activity, "无 OCR 文本", Toast.LENGTH_SHORT).show()
            return
        }

        // 1️⃣ 询问是否语音补充（与 main 分支 CropFragment 一致）
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音补充")
            .setMessage("是否添加语音补充来辅助识别？")
            .setPositiveButton("是") { _, _ ->
                startVoiceInput { voiceText ->
                    doAiExtract(activity, rawText, voiceText)
                }
            }
            .setNegativeButton("否") { _, _ ->
                doAiExtract(activity, rawText, "")
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 执行 AI 提取（语音补充已决定后）
     */
    private fun doAiExtract(activity: DrugEntryActivity, rawText: String, voiceText: String) {
        binding.aiAutoFillCard.isEnabled = false
        binding.aiAutoFillCard.isClickable = false
        binding.aiAutoFillCard.alpha = 0.7f
        binding.confirmButton.isEnabled = false
        binding.skipButton.isEnabled = false

        val client = DeepSeekClient.create(requireContext())
        if (client == null) {
            Toast.makeText(activity, "AI 未配置，请设置 API Key", Toast.LENGTH_LONG).show()
            resetAiButton()
            return
        }

        Thread {
            try {
                val result = client.extractDrugInfo(rawText, ocrLines, voiceText)

                // 无论成功/失败，都保存请求和响应到 session（供调试页展示）
                val fullRequest = buildString {
                    append("=== System Prompt ===\n")
                    append(result.systemPrompt)
                    append("\n\n=== User Message ===\n")
                    append(result.formattedInput)
                }
                activity.updateSession {
                    it.copy(
                        llmRequestText = fullRequest,
                        llmResponseJson = result.rawApiResponse
                    )
                }

                activity.runOnUiThread {
                    if (result.success) {
                        if (result.drugInfo.hasAnyValue) {
                            activity.updateDrugInfo(result.drugInfo, FieldStatus.RECOGNIZED)
                            activity.clearRetake()
                            activity.navigateTo(DrugEntryStep.COMPLETION)
                        } else {
                            Toast.makeText(
                                activity, "AI 未能提取到字段信息，请重试或手动选择",
                                Toast.LENGTH_LONG
                            ).show()
                            resetAiButton()
                        }
                    } else {
                        val errMsg = result.error.ifBlank { "AI 提取失败" }
                        Toast.makeText(activity, errMsg, Toast.LENGTH_LONG).show()
                        resetAiButton()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI extraction failed", e)
                activity.runOnUiThread {
                    Toast.makeText(
                        activity, "AI 提取失败: ${e.message?.take(50)}", Toast.LENGTH_LONG
                    ).show()
                    resetAiButton()
                }
            }
        }.start()
    }

    private fun resetAiButton() {
        binding.aiAutoFillCard.isEnabled = true
        binding.aiAutoFillCard.isClickable = true
        binding.aiAutoFillCard.alpha = 1.0f
        binding.confirmButton.isEnabled = fieldAssignments.isNotEmpty()
        binding.skipButton.isEnabled = true
    }

    // ==================== 清理 ====================

    override fun onDestroyView() {
        super.onDestroyView()
        stopVoiceInput()
        bottomSheet?.dismiss()
        bottomSheet = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
