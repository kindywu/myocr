package com.example.myocr.drugentry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.myocr.OcrEngine
import com.example.myocr.OcrLine
import com.example.myocr.OcrResult
import com.example.myocr.R
import java.io.File
import java.util.concurrent.Executors

/**
 * 单字段 OCR 裁剪页（重拍模式）
 *
 * 在 CaptureCompleteFragment 中对某个字段点击补拍后进入，用于单字段重拍。
 * 显示全屏图片，用户通过拖拽选择矩形识别区域。
 * 确认后对选区进行 OCR 识别，然后调用 DeepSeek LLM 提取结构化药品信息，
 * 最后跳转回补全页。
 *
 * OCR 识别完成后、LLM 提取之前，弹窗询问是否添加语音补充（可选）。
 * 用户可选择说话补充信息，语音文本经确认后与 OCR 文本一起发给 LLM。
 */
class SingleFieldCropFragment : Fragment() {

    companion object {
        private const val TAG = "SingleFieldCropFragment"
    }

    private var _binding: com.example.myocr.databinding.FragmentCropBinding? = null
    private val binding get() = _binding!!
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private var ocrEngine: OcrEngine? = null
    private var deepSeekClient: DeepSeekClient? = null
    private var sourceBitmap: android.graphics.Bitmap? = null

    // ==================== 语音识别 ====================

    private var speechRecognizer: SpeechRecognizer? = null
    /** 语音输入期间的临时对话框 */
    private var voiceListeningDialog: androidx.appcompat.app.AlertDialog? = null
    /** 语音补充确认后的回调 */
    private var onVoiceCompleted: ((String) -> Unit)? = null

    /** 音波可视化条 */
    private val waveBars = mutableListOf<View>()
    /** 脉冲环视图引用 */
    private var pulseRing1View: View? = null
    private var pulseRing2View: View? = null
    /** 脉冲环动画 */
    private var pulseAnimator1: android.animation.Animator? = null
    private var pulseAnimator2: android.animation.Animator? = null

    /** 用户是否正在说话（控制动画启停） */
    private var isSpeaking = false

    /** 逐字打印相关 */
    private var lastPartialText = ""
    private var typewriterRunnable: Runnable? = null

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

            override fun onBeginningOfSpeech() {
                // 检测到用户开始说话 → 启动脉冲环动画、激活音波条
                isSpeaking = true
                startPulseAnimations()
            }

            override fun onEndOfSpeech() {
                // 用户停止说话 → 暂停动画、重置音波条
                isSpeaking = false
                stopPulseAnimations()
                resetWaveBars()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (isSpeaking) {
                    updateWaveBars(rmsdB)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    typewriterPartialText(matches[0])
                }
            }

            override fun onResults(results: Bundle?) {
                dismissVoiceListeningDialog()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    showVoiceConfirmDialog(matches[0])
                } else {
                    Toast.makeText(requireContext(), "未听清，请重试", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(error: Int) {
                dismissVoiceListeningDialog()
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

    /** 启动语音输入（需先有权限） */
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

    /** 显示"正在聆听…"对话框（初始无动画，声音触发后激活） */
    private fun showVoiceListeningDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_voice_input, null)

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

        // 保存脉冲环视图引用（供开始/停止说话时启停动画）
        pulseRing1View = dialogView.findViewById(R.id.pulseRing1)
        pulseRing2View = dialogView.findViewById(R.id.pulseRing2)
        // 初始时脉冲环不可见，等用户说话才出现
        pulseRing1View?.alpha = 0f
        pulseRing2View?.alpha = 0f

        // 取消按钮
        dialogView.findViewById<Button>(R.id.cancelButton).setOnClickListener { stopVoiceInput() }

        voiceListeningDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { stopVoiceInput() }
            .show()
    }

    /** 用户开始说话 → 启动脉冲环动画 */
    private fun startPulseAnimations() {
        val r1 = pulseRing1View ?: return
        val r2 = pulseRing2View ?: return
        stopPulseAnimations() // 防止重复启动
        pulseAnimator1 = buildPulseAnimation(r1, 1200, 1.0f, 1.8f)
        pulseAnimator2 = buildPulseAnimation(r2, 1200, 1.0f, 1.4f)
    }

    /** 用户停止说话 → 暂停脉冲环动画、淡出环体 */
    private fun stopPulseAnimations() {
        pulseAnimator1?.cancel()
        pulseAnimator1 = null
        pulseAnimator2?.cancel()
        pulseAnimator2 = null
        pulseRing1View?.animate()?.alpha(0f)?.setDuration(200)?.start()
        pulseRing2View?.animate()?.alpha(0f)?.setDuration(200)?.start()
    }

    /** 构建并启动脉冲环动画 */
    private fun buildPulseAnimation(
        view: View,
        duration: Long,
        fromScale: Float,
        toScale: Float
    ): android.animation.Animator {
        // 先让环可见
        view.alpha = 0.6f

        val scaleX = android.animation.ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, toScale).apply {
            this.duration = duration
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        val scaleY = android.animation.ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, toScale).apply {
            this.duration = duration
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        val alpha = android.animation.ObjectAnimator.ofFloat(view, View.ALPHA, 0.6f, 0.0f).apply {
            this.duration = duration
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        val set = android.animation.AnimatorSet().apply { playTogether(scaleX, scaleY, alpha) }
        set.start()
        return set
    }

    /** 逐字打印语音识别中间结果 */
    private fun typewriterPartialText(newText: String) {
        val textView = voiceListeningDialog?.findViewById<TextView>(R.id.partialResult) ?: return

        // 取消上次未完成的逐字打印
        typewriterRunnable?.let { textView.removeCallbacks(it) }
        typewriterRunnable = null

        if (newText.startsWith(lastPartialText) && lastPartialText.isNotEmpty()) {
            // 平滑扩展：只打印新增的字
            var charIndex = lastPartialText.length
            val runnable = object : Runnable {
                override fun run() {
                    if (charIndex < newText.length) {
                        textView.text = newText.substring(0, charIndex + 1)
                        charIndex++
                        textView.postDelayed(this, 25L)
                    }
                }
            }
            typewriterRunnable = runnable
            textView.post(runnable)
        } else {
            // 识别引擎修正了之前的结果（完全不同），直接显示
            textView.text = newText
        }
        lastPartialText = newText
    }

    /** 根据音量更新音波条高度 */
    private fun updateWaveBars(rmsdB: Float) {
        val normalized = (rmsdB / 12.0f).coerceIn(0f, 1f)
        for ((i, bar) in waveBars.withIndex()) {
            val offset = ((i - 2) * 0.12f)
            val scale = 0.3f + (normalized + offset).coerceIn(0f, 1f) * 1.7f
            bar.scaleY = scale
        }
    }

    private fun resetWaveBars() {
        for (bar in waveBars) {
            bar.scaleY = 1.0f
        }
    }

    private fun dismissVoiceListeningDialog() {
        typewriterRunnable?.let { voiceListeningDialog?.findViewById<TextView>(R.id.partialResult)?.removeCallbacks(it) }
        typewriterRunnable = null
        stopPulseAnimations()
        voiceListeningDialog?.dismiss()
        voiceListeningDialog = null
        resetWaveBars()
        isSpeaking = false
        lastPartialText = ""
    }

    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        dismissVoiceListeningDialog()
    }

    /** 语音识别结果确认对话框 */
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
        _binding = com.example.myocr.databinding.FragmentCropBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as DrugEntryActivity

        // 返回
        binding.backButton.setOnClickListener { activity.supportFragmentManager.popBackStack() }

        // 从 session 获取照片路径
        val photoPath = activity.session.pendingPhotoPath
        if (photoPath.isNotBlank()) {
            val rawBitmap = BitmapFactory.decodeFile(photoPath)
            sourceBitmap = rotateBitmapIfNeeded(photoPath, rawBitmap)
            binding.cropOverlay.sourceBitmap = sourceBitmap
        }

        // 重拍 → 返回拍照页
        binding.retakeButton.setOnClickListener {
            sourceBitmap?.recycle()
            sourceBitmap = null
            activity.supportFragmentManager.popBackStack()
        }

        // 确认识别 → 裁剪 + OCR
        binding.confirmButton.setOnClickListener {
            binding.confirmButton.isEnabled = false
            binding.confirmButton.text = "识别中…"

            val cropped = binding.cropOverlay.cropBitmap()
            if (cropped == null) {
                Log.w(TAG, "Crop region too small, using full image")
                val photoFile = File(photoPath)
                processFullImage(activity, photoFile)
            } else {
                Log.d(TAG, "Cropped region: ${cropped.width}x${cropped.height}")
                processCroppedImage(activity, cropped, photoPath)
            }
        }

        // 初始化 OCR
        initOcr()
    }

    private fun initOcr() {
        ocrExecutor.execute {
            try {
                ocrEngine = OcrEngine.create(requireContext())
                Log.d(TAG, "OCR engine ready for crop")
            } catch (e: Exception) {
                Log.e(TAG, "OCR init failed", e)
            }
            try {
                deepSeekClient = DeepSeekClient.create(requireContext())
                if (deepSeekClient != null) {
                    Log.d(TAG, "DeepSeek LLM client ready")
                } else {
                    Log.d(TAG, "DeepSeek LLM not configured — fields left for manual entry")
                }
            } catch (e: Exception) {
                Log.w(TAG, "DeepSeek init failed", e)
            }
        }
    }

    private fun processCroppedImage(activity: DrugEntryActivity, croppedBmp: android.graphics.Bitmap, photoPath: String) {
        ocrExecutor.execute {
            try {
                val ocrResult = ocrEngine?.recognizeStructured(croppedBmp) ?: OcrResult("", emptyList())
                handleOcrResult(activity, ocrResult.fullText, ocrResult.lines, photoPath)
            } catch (e: Exception) {
                Log.e(TAG, "Cropped OCR failed, fallback to full image", e)
                processFullImage(activity, File(photoPath))
            } finally {
                croppedBmp.recycle()
            }
        }
    }

    /**
     * 根据 EXIF 信息旋转图片，保证显示方向与拍摄时一致
     */
    private fun rotateBitmapIfNeeded(photoPath: String, bitmap: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (bitmap == null) return null
        return try {
            val exif = ExifInterface(photoPath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate bitmap", e)
            bitmap
        }
    }

    private fun processFullImage(activity: DrugEntryActivity, photoFile: File) {
        ocrExecutor.execute {
            try {
                val raw = BitmapFactory.decodeFile(photoFile.absolutePath)
                val bitmap = rotateBitmapIfNeeded(photoFile.absolutePath, raw)
                val ocrResult = if (bitmap != null && ocrEngine != null) {
                    ocrEngine!!.recognizeStructured(bitmap)
                } else OcrResult("", emptyList())
                bitmap?.recycle()
                handleOcrResult(activity, ocrResult.fullText, ocrResult.lines, photoFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Full image OCR failed", e)
                navigateAfterOcr(activity)
            }
        }
    }

    /**
     * 处理 OCR 结果 → 询问是否语音补充 → LLM 提取 → 导航
     *
     * OCR 识别完成后，先保存 OCR 文本到 session，然后在 UI 线程弹窗询问用户
     * 是否需要添加语音补充。根据用户选择决定是否启动语音识别，最后将
     * OCR 文本（+ 可选语音文本）发给 LLM 提取结构化信息。
     */
    private fun handleOcrResult(activity: DrugEntryActivity, rawText: String, ocrLines: List<OcrLine>, photoPath: String) {
        Log.d(TAG, "OCR result: ${rawText.take(100)}, lines: ${ocrLines.size}")

        activity.updateSession { it.copy(rawOcrText = rawText) }

        val client = deepSeekClient
        if (client == null || rawText.isBlank()) {
            Log.d(TAG, "LLM not available or blank text — fields left for manual entry")
            navigateAfterOcr(activity)
            return
        }

        // 切换到 UI 线程弹窗询问是否语音补充
        activity.runOnUiThread {
            if (!isAdded) return@runOnUiThread

            Log.d(TAG, "Showing voice supplement dialog (OCR done, before LLM)")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("语音补充")
                .setMessage("是否添加语音补充来辅助识别？")
                .setPositiveButton("是") { _, _ ->
                    Log.d(TAG, "User chose voice supplement: YES")
                    // 启动语音输入，完成后继续 LLM 提取
                    startVoiceInput { voiceText ->
                        val logVoice = if (voiceText.isNotBlank()) voiceText else "(用户放弃/空)"
                        Log.d(TAG, "Voice supplement result: [$logVoice]")
                        proceedWithLlm(activity, client, rawText, ocrLines, voiceText)
                    }
                }
                .setNegativeButton("否") { _, _ ->
                    Log.d(TAG, "User chose voice supplement: NO")
                    proceedWithLlm(activity, client, rawText, ocrLines, "")
                }
                .setCancelable(false)
                .show()
        }
    }

    /**
     * 执行 LLM 提取 → 更新 session → 导航
     *
     * @param voiceText 用户语音补充文本（可能为空字符串）
     */
    private fun proceedWithLlm(
        activity: DrugEntryActivity,
        client: DeepSeekClient,
        rawText: String,
        ocrLines: List<OcrLine>,
        voiceText: String
    ) {
        if (voiceText.isNotBlank()) {
            // 存入已采纳的语音文本
            activity.updateSession { session ->
                session.copy(fieldVoiceInputs = mapOf("_global" to voiceText))
            }
        }

        // 切到后台线程执行 LLM API 调用
        ocrExecutor.execute {
            try {
                llmExtractDrugInfo(activity, client, rawText, ocrLines, voiceText)
            } catch (e: Exception) {
                Log.e(TAG, "LLM extraction failed, fields left for manual entry", e)
            }
            navigateAfterOcr(activity)
        }
    }

    /**
     * DeepSeek LLM 提取药品信息（全字段提取 → 按 retakeFieldTarget 更新单字段）
     *
     * @param voiceText 用户语音补充文本（可选，为空则仅 OCR）
     */
    private fun llmExtractDrugInfo(
        activity: DrugEntryActivity,
        client: DeepSeekClient,
        rawText: String,
        ocrLines: List<OcrLine>,
        voiceText: String = ""
    ) {
        if (voiceText.isNotBlank()) {
            Log.d(TAG, "LLM call WITH voice supplement: [$voiceText] (${voiceText.length} chars)")
        } else {
            Log.d(TAG, "LLM call WITHOUT voice supplement (OCR only)")
        }
        val fullResult = client.extractDrugInfo(rawText, ocrLines, userVoiceText = voiceText)
        Log.d(TAG, "Full extraction: success=${fullResult.success}, " +
                "drugName=[${fullResult.drugInfo.drugName}] " +
                "expiry=[${fullResult.drugInfo.expiryDate}] " +
                "mfg=[${fullResult.drugInfo.manufacturer}] " +
                "batch=[${fullResult.drugInfo.batchNumber}]")

        val voiceInformed = fullResult.formattedInput.let { input ->
            if (input.contains(voiceText) && voiceText.length > 2) "✓ voice adopted" else "⚠ voice NOT in prompt"
        }
        Log.d(TAG, "Voice supplement check: $voiceInformed")

        if (!fullResult.success) {
            val errMsg = fullResult.error.ifBlank { "LLM 提取失败" }
            Log.w(TAG, "LLM full extraction failed: $errMsg")
            activity.runOnUiThread {
                if (isAdded) android.widget.Toast.makeText(activity, errMsg, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        val info = fullResult.drugInfo
        val llmJson = if (fullResult.success) fullResult.rawApiResponse else ""

        // 更新 session（单字段重拍模式下只更新 retakeFieldTarget 指定的字段）
        activity.updateSession { session ->
            val targetField = session.retakeFieldTarget
            val finalInfo: DrugInfo
            val newStatuses = session.fieldStatuses.toMutableMap()

            if (targetField != null) {
                val current = session.drugInfo
                finalInfo = when (targetField) {
                    "drugName" -> current.copy(drugName = info.drugName.ifBlank { current.drugName })
                    "expiryDate" -> current.copy(expiryDate = info.expiryDate.ifBlank { current.expiryDate })
                    "manufacturer" -> current.copy(manufacturer = info.manufacturer.ifBlank { current.manufacturer })
                    "batchNumber" -> current.copy(batchNumber = info.batchNumber.ifBlank { current.batchNumber })
                    else -> current
                }
                val targetValue = when (targetField) {
                    "drugName" -> finalInfo.drugName
                    "expiryDate" -> finalInfo.expiryDate
                    "manufacturer" -> finalInfo.manufacturer
                    "batchNumber" -> finalInfo.batchNumber
                    else -> ""
                }
                if (targetValue.isNotBlank()) newStatuses[targetField] = FieldStatus.RECOGNIZED
            } else {
                // 兜底：无 targetField 时全字段合并
                finalInfo = session.drugInfo.mergeWith(info)
                if (finalInfo.drugName.isNotBlank()) newStatuses["drugName"] = FieldStatus.RECOGNIZED
                if (finalInfo.expiryDate.isNotBlank()) newStatuses["expiryDate"] = FieldStatus.RECOGNIZED
                if (finalInfo.manufacturer.isNotBlank()) newStatuses["manufacturer"] = FieldStatus.RECOGNIZED
                if (finalInfo.batchNumber.isNotBlank()) newStatuses["batchNumber"] = FieldStatus.RECOGNIZED
            }

            session.copy(
                drugInfo = finalInfo,
                fieldStatuses = newStatuses,
                llmResponseJson = llmJson
            )
        }

        val filled = info.filledFieldCount
        Log.d(TAG, "LLM extraction complete: $filled/4 fields filled")
    }

    private fun navigateAfterOcr(activity: DrugEntryActivity) {
        activity.runOnUiThread {
            if (!isAdded) return@runOnUiThread

            val nextStep = if (activity.isRetakeMode()) {
                activity.getRetakeSource() ?: DrugEntryStep.COMPLETION
            } else {
                DrugEntryStep.COMPLETION
            }
            activity.clearRetake()
            activity.navigateTo(nextStep)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceInput()
        speechRecognizer?.destroy()
        speechRecognizer = null
        ocrExecutor.shutdown()
        ocrEngine?.close()
        sourceBitmap?.recycle()
        sourceBitmap = null
    }
}
