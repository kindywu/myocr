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

    /** 显示"正在聆听…"对话框 */
    private fun showVoiceListeningDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_voice_input, null)

        // 隐藏不需要的元素（脉冲环动画等），只保留文字
        dialogView.findViewById<View>(R.id.pulseRing1)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.pulseRing2)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.waveContainer)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.micContainer)?.visibility = View.GONE

        voiceListeningDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { stopVoiceInput() }
            .show()
    }

    private fun dismissVoiceListeningDialog() {
        voiceListeningDialog?.dismiss()
        voiceListeningDialog = null
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

            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("语音补充")
                .setMessage("是否添加语音补充来辅助识别？")
                .setPositiveButton("是") { _, _ ->
                    // 启动语音输入，完成后继续 LLM 提取
                    startVoiceInput { voiceText ->
                        proceedWithLlm(activity, client, rawText, ocrLines, voiceText)
                    }
                }
                .setNegativeButton("否") { _, _ ->
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
        val fullResult = client.extractDrugInfo(rawText, ocrLines, userVoiceText = voiceText)
        Log.d(TAG, "Full extraction: success=${fullResult.success}, " +
                "drugName=[${fullResult.drugInfo.drugName}] " +
                "expiry=[${fullResult.drugInfo.expiryDate}] " +
                "mfg=[${fullResult.drugInfo.manufacturer}] " +
                "batch=[${fullResult.drugInfo.batchNumber}]")

        if (voiceText.isNotBlank()) {
            Log.d(TAG, "Voice text provided: [$voiceText] (${voiceText.length} chars)")
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
