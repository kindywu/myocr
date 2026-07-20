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
import android.view.animation.AccelerateDecelerateInterpolator
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
import com.example.myocr.drugentry.DrugOcrParser
import java.io.File
import java.util.concurrent.Executors

/**
 * 单字段 OCR 裁剪页（重拍模式）
 *
 * 在 CaptureCompleteFragment 中对某个字段点击补拍后进入，用于单字段重拍。
 * 显示全屏图片，用户通过拖拽选择矩形识别区域。
 * 进入后自动弹出语音输入对话框，用户可直接口述字段值。
 * 确认后对选区进行 OCR 识别，然后规则提取快速填充字段，
 * 最后跳转回补全页。
 */
class SingleFieldCropFragment : Fragment() {

    companion object {
        private const val TAG = "SingleFieldCropFragment"

        private val FIELD_LABELS = mapOf(
            "drugName" to "药品名称",
            "expiryDate" to "有效期至",
            "manufacturer" to "生产厂家",
            "batchNumber" to "批号"
        )
    }

    private var _binding: com.example.myocr.databinding.FragmentCropBinding? = null
    private val binding get() = _binding!!
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private var ocrEngine: OcrEngine? = null
    private var sourceBitmap: android.graphics.Bitmap? = null

    // ==================== 语音识别 ====================

    /** 当前正在语音输入的字段 key */
    private var voiceFieldKey: String = ""

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
                ).show()
            }
        }

    /** 创建 SpeechRecognizer 并设置监听器 */
    private fun createSpeechRecognizer(): SpeechRecognizer {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onEndOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onRmsChanged(rmsdB: Float) {
                    updateWaveBars(rmsdB)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches =
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        voiceDialog?.findViewById<TextView>(R.id.partialResult)?.text = matches[0]
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

        fieldLabel.text = "正在听「${fieldLabel(fieldKey)}」"

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
        view: View, duration: Long, fromScale: Float, toScale: Float
    ): android.animation.Animator {
        val scaleX =
            android.animation.ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, toScale).apply {
                this.duration = duration
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.RESTART
            }
        val scaleY =
            android.animation.ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, toScale).apply {
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
        val normalized = (rmsdB / 12.0f).coerceIn(0f, 1f)
        for ((i, bar) in waveBars.withIndex()) {
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
        for (bar in waveBars) {
            bar.scaleY = 1.0f
        }
    }

    /** 启动语音输入 */
    private fun startVoiceInput(fieldKey: String) {
        voiceFieldKey = fieldKey
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        doStartVoiceInput(fieldKey)
    }

    /** 已有权限，执行语音输入 */
    private fun doStartVoiceInput(fieldKey: String) {
        voiceFieldKey = fieldKey
        if (speechRecognizer == null) {
            speechRecognizer = createSpeechRecognizer()
        }
        showVoiceDialog(fieldKey)
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
            dismissVoiceDialog()
        }
    }

    /** 语音识别成功后的处理 */
    private fun onVoiceResult(fieldKey: String, spokenText: String) {
        val activity = requireActivity() as DrugEntryActivity

        // 保存语音结果到 session（作为 OCR 补充）
        val currentVoiceInputs = activity.session.fieldVoiceInputs
        activity.updateSession {
            it.copy(fieldVoiceInputs = currentVoiceInputs + (fieldKey to spokenText))
        }

        // 如果当前字段为空，直接填入
        val currentValue = when (fieldKey) {
            "drugName" -> activity.session.drugInfo.drugName
            "expiryDate" -> activity.session.drugInfo.expiryDate
            "manufacturer" -> activity.session.drugInfo.manufacturer
            "batchNumber" -> activity.session.drugInfo.batchNumber
            else -> ""
        }

        if (currentValue.isBlank()) {
            val updated = when (fieldKey) {
                "drugName" -> activity.session.drugInfo.copy(drugName = spokenText)
                "expiryDate" -> activity.session.drugInfo.copy(expiryDate = spokenText)
                "manufacturer" -> activity.session.drugInfo.copy(manufacturer = spokenText)
                "batchNumber" -> activity.session.drugInfo.copy(batchNumber = spokenText)
                else -> activity.session.drugInfo
            }
            activity.updateDrugInfo(updated, FieldStatus.MANUAL)
            Toast.makeText(requireContext(), "已通过语音填入「$spokenText」", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "语音识别: $spokenText", Toast.LENGTH_SHORT).show()
        }
    }

    /** 显示语音输入确认对话框 */
    private var voiceConfirmDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showVoiceConfirmDialog(fieldKey: String) {
        voiceConfirmDialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("语音输入 ${fieldLabel(fieldKey)}")
                .setMessage("是否要用语音输入「${fieldLabel(fieldKey)}」？")
                .setPositiveButton("开始录音") { _, _ ->
                    startVoiceInput(fieldKey)
                }
                .setNegativeButton("跳过") { _, _ ->
                    // 什么都不做，让用户继续拍照
                }
                .setCancelable(false)
                .show()
    }

    private fun fieldLabel(fieldKey: String): String = FIELD_LABELS[fieldKey] ?: fieldKey

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
        binding.backButton.setOnClickListener { stopVoiceInput(); activity.supportFragmentManager.popBackStack() }

        // 从 session 获取照片路径
        val photoPath = activity.session.pendingPhotoPath
        if (photoPath.isNotBlank()) {
            val rawBitmap = BitmapFactory.decodeFile(photoPath)
            sourceBitmap = rotateBitmapIfNeeded(photoPath, rawBitmap)
            binding.cropOverlay.sourceBitmap = sourceBitmap
        }

        // 重拍 → 返回拍照页
        binding.retakeButton.setOnClickListener {
            stopVoiceInput()
            sourceBitmap?.recycle()
            sourceBitmap = null
            activity.supportFragmentManager.popBackStack()
        }

        // 确认识别 → 裁剪 + OCR
        binding.confirmButton.setOnClickListener {
            stopVoiceInput()
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

        // 初始化 OCR 并自动弹出语音输入
        initOcr()

        // 延迟弹出语音输入确认对话框（等待页面加载完成）
        binding.confirmButton.postDelayed({
            if (isAdded) {
                val targetField = activity.session.retakeFieldTarget
                if (targetField != null) {
                    showVoiceConfirmDialog(targetField)
                }
            }
        }, 600)
    }

    private fun initOcr() {
        ocrExecutor.execute {
            try {
                ocrEngine = OcrEngine.create(requireContext())
                Log.d(TAG, "OCR engine ready for crop")
            } catch (e: Exception) {
                Log.e(TAG, "OCR init failed", e)
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
     * 处理 OCR 结果 → 规则提取 → 直接跳回补全页
     *
     * 单字段重拍不走 OCR 选择页，直接规则提取并跳回触发重拍页。
     */
    private fun handleOcrResult(activity: DrugEntryActivity, rawText: String, ocrLines: List<OcrLine>, photoPath: String) {
        Log.d(TAG, "OCR result: ${rawText.take(100)}, lines: ${ocrLines.size}")

        activity.updateSession { it.copy(rawOcrText = rawText) }

        // 规则提取直接填充
        val drugInfo = DrugOcrParser.quickExtract(ocrLines)
        Log.d(TAG, "Quick extract: name=[${drugInfo.drugName}] expiry=[${drugInfo.expiryDate}] " +
                "mfg=[${drugInfo.manufacturer}] batch=[${drugInfo.batchNumber}]")

        activity.updateDrugInfo(drugInfo, FieldStatus.RECOGNIZED)

        navigateAfterOcr(activity)
    }

    private fun navigateAfterOcr(activity: DrugEntryActivity) {
        activity.runOnUiThread {
            if (!isAdded) return@runOnUiThread

            val nextStep = activity.getRetakeSource() ?: DrugEntryStep.COMPLETION
            activity.clearRetake()
            activity.navigateTo(nextStep)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopVoiceInput()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        ocrExecutor.shutdown()
        ocrEngine?.close()
        sourceBitmap?.recycle()
        sourceBitmap = null
    }
}
