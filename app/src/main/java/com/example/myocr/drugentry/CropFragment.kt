package com.example.myocr.drugentry

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.myocr.OcrEngine
import com.example.myocr.OcrLine
import com.example.myocr.OcrResult
import java.io.File
import java.util.concurrent.Executors

/**
 * 全字段 OCR 选区裁剪页
 *
 * 拍照后显示全屏图片，用户通过拖拽选择矩形识别区域。
 * 确认后对选区进行 OCR 识别，然后调用 DeepSeek LLM 提取结构化药品信息，
 * 最后跳转到补全页展示结果。
 *
 * 本页面向首次采集和全字段重拍场景，不包含语音补充环节。
 * 单字段重拍请参见 [SingleFieldCropFragment]。
 */
class CropFragment : Fragment() {

    companion object {
        private const val TAG = "CropFragment"
    }

    private var _binding: com.example.myocr.databinding.FragmentCropBinding? = null
    private val binding get() = _binding!!
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private var ocrEngine: OcrEngine? = null
    private var deepSeekClient: DeepSeekClient? = null
    private var sourceBitmap: android.graphics.Bitmap? = null

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
     * 处理 OCR 结果 → LLM 提取 → 导航
     *
     * OCR 识别完成后，直接调用 DeepSeek LLM 提取结构化药品信息。
     * 全字段场景不需要语音补充环节（单字段重拍请参见 SingleFieldCropFragment）。
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

        // 全字段 OCR：直接执行 LLM 提取，无需语音补充
        proceedWithLlm(activity, client, rawText, ocrLines, "")
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
     * DeepSeek LLM 提取药品信息（全字段提取）
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

        if (!fullResult.success) {
            val errMsg = fullResult.error.ifBlank { "LLM 提取失败" }
            Log.w(TAG, "LLM full extraction failed: $errMsg")
            activity.runOnUiThread {
                if (isAdded) android.widget.Toast.makeText(activity, errMsg, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        val info = fullResult.drugInfo
        val llmJson = if (fullResult.success) fullResult.rawApiResponse else ""

        // 更新 session
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
        Log.d(TAG, "LLM full extraction complete: $filled/4 fields filled")
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
        ocrExecutor.shutdown()
        ocrEngine?.close()
        sourceBitmap?.recycle()
        sourceBitmap = null
    }
}
