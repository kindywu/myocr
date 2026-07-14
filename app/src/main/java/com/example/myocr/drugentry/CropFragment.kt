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
 * 确认后对选区进行 OCR 识别，然后跳转到结果页。
 */
class CropFragment : Fragment() {

    companion object {
        private const val TAG = "CropFragment"
    }

    private var _binding: com.example.myocr.databinding.FragmentCropBinding? = null
    private val binding get() = _binding!!
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private var ocrEngine: OcrEngine? = null
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
                ocrEngine = OcrEngine.create()
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

    private fun handleOcrResult(activity: DrugEntryActivity, rawText: String, ocrLines: List<OcrLine>, photoPath: String) {
        Log.d(TAG, "OCR result: ${rawText.take(100)}, lines: ${ocrLines.size}")

        // 保存原始 OCR 文本到 session（用于调试展示）
        activity.updateSession { it.copy(rawOcrText = rawText) }

        if (rawText.isNotBlank()) {
            // 1️⃣ 获取用户语音输入的药品名称（辅助 LLM）
            val voiceInput = activity.session.voiceInputDrugName

            // 2️⃣ LLM 解析（可选，有 API key 才走）
            //    不再使用正则解析，完全信任 LLM 的判断
            var finalParsed = DrugInfo()
            var finalCandidates = emptyMap<String, DeepSeekClient.FieldCandidates>()
            val llmClient = activity.getDeepSeekClient()
            if (llmClient != null) {
                try {
                    val llmResult = llmClient.extractDrugInfo(rawText, ocrLines, voiceInput)
                    if (llmResult.success) {
                        finalCandidates = llmResult.allCandidates
                        finalParsed = llmResult.drugInfo
                        // 保存 LLM 输入/响应到 session（调试用）
                        activity.updateSession {
                            it.copy(
                                llmFormattedInput = llmResult.formattedInput,
                                rawApiResponse = llmResult.rawApiResponse
                            )
                        }
                        Log.d(TAG, "LLM result: drugName=[${finalParsed.drugName}]")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "LLM failed, fields left empty for manual fill", e)
                }
            }

            // 3️⃣ 更新 session（仅 LLM 有结果时才写入，否则留给用户手动填写）
            if (finalParsed.drugName.isNotBlank() || finalParsed.hasAnyValue) {
                activity.updateDrugInfo(finalParsed, FieldStatus.RECOGNIZED)
            }
            if (finalCandidates.isNotEmpty()) {
                activity.setLlmCandidates(finalCandidates)
            }
        }

        navigateAfterOcr(activity)
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
