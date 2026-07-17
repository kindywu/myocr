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
import com.example.myocr.R
import java.io.File
import java.util.concurrent.Executors

/**
 * 选区裁剪页
 *
 * 拍照后显示全屏图片，用户通过拖拽选择矩形识别区域。
 * 确认后对选区进行 OCR 识别，然后调用 DeepSeek LLM 提取结构化药品信息，
 * 最后跳转到补全页展示结果。
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
                // 选区过小，用整图
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
            // 同时初始化 DeepSeek LLM 客户端（未配置 Key 时返回 null）
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
     * 处理 OCR 结果 → 调用 LLM 提取字段 → 更新 session → 导航
     *
     * 提取策略：
     * 1. 先全字段提取（一次 API 调用）
     * 2. 对空字段逐个单字段补提（精准兜底）
     * 3. LLM 不可用或失败时跳过，字段留空供手动填写
     */
    private fun handleOcrResult(activity: DrugEntryActivity, rawText: String, ocrLines: List<OcrLine>, photoPath: String) {
        Log.d(TAG, "OCR result: ${rawText.take(100)}, lines: ${ocrLines.size}")

        activity.updateSession { it.copy(rawOcrText = rawText) }

        // DeepSeek LLM 提取
        val client = deepSeekClient
        if (client != null && rawText.isNotBlank()) {
            try {
                llmExtractDrugInfo(activity, client, rawText, ocrLines)
            } catch (e: Exception) {
                Log.e(TAG, "LLM extraction failed, fields left for manual entry", e)
            }
        } else {
            Log.d(TAG, "LLM not available — fields left for manual entry")
        }

        navigateAfterOcr(activity)
    }

    /**
     * DeepSeek LLM 提取药品信息（全字段提取）
     *
     * 仅做一次全字段 API 调用。单字段提取由用户在补全页点击字段时触发。
     */
    private fun llmExtractDrugInfo(
        activity: DrugEntryActivity,
        client: DeepSeekClient,
        rawText: String,
        ocrLines: List<OcrLine>
    ) {
        val fullResult = client.extractDrugInfo(rawText, ocrLines)
        Log.d(TAG, "Full extraction: success=${fullResult.success}, " +
                "drugName=[${fullResult.drugInfo.drugName}] " +
                "expiry=[${fullResult.drugInfo.expiryDate}] " +
                "mfg=[${fullResult.drugInfo.manufacturer}] " +
                "batch=[${fullResult.drugInfo.batchNumber}]")

        val info = fullResult.drugInfo
        val llmJson = if (fullResult.success) fullResult.rawApiResponse else ""

        // 更新 session
        // - 重拍模式: 只更新目标字段，保留其他已有值
        // - 普通模式: 全字段合并
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

            // 重拍模式 → 跳回重拍来源页；否则 → 补全页
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
