package com.example.myocr.drugentry

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.myocr.R
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 拍照采集页
 *
 * 全屏相机取景，带取景框覆盖层。
 * 拍照后立即提交 OCR 识别，然后跳转到多角度补全页。
 */
class CaptureGuideFragment : Fragment() {

    private var _binding: com.example.myocr.databinding.FragmentCaptureGuideBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** 是否使用前置摄像头 */
    private var useFrontCamera: Boolean = false

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 使用 ActivityResultContracts 请求相机权限（modern API）
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (isAdded) startCamera()
        } else {
            if (isAdded) requireActivity().supportFragmentManager.popBackStack()
        }
    }

    /** 语音输入结果回调 */
    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                onVoiceResult(matches[0])
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = com.example.myocr.databinding.FragmentCaptureGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 更新步骤指示
        binding.captureHint.text = getString(R.string.capture_front_hint)
        binding.stepText.text = getString(R.string.capture_step, 1)

        // 返回
        binding.backButton.setOnClickListener {
            if (isAdded) requireActivity().supportFragmentManager.popBackStack()
        }

        // 语音输入药品名称
        binding.voiceInputButton.setOnClickListener { startVoiceInput() }

        // 恢复 session 中已有的语音输入
        val captureActivity = requireActivity() as DrugEntryActivity
        if (captureActivity.session.voiceInputDrugName.isNotBlank()) {
            onVoiceResult(captureActivity.session.voiceInputDrugName)
        }

        // 拍照
        binding.captureButton.setOnClickListener { takePhoto() }

        // 切换前后摄像头
        binding.switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }

        // 启动相机（用 view.post 确保视图已就绪）
        view.post { checkCameraAndStart() }
    }

    /** 启动系统语音识别 */
    private fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_input_prompt))
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
    private fun onVoiceResult(spokenName: String) {
        // 按钮变绿色表示已识别
        binding.voiceInputButton.setBackgroundResource(R.drawable.voice_button_active)
        // 提示文字显示识别结果
        binding.captureHint.text = "已语音输入：$spokenName"

        // 存入 session，后续传给 LLM 辅助判断
        if (isAdded) {
            val activity = requireActivity() as DrugEntryActivity
            activity.updateSession { it.copy(voiceInputDrugName = spokenName) }
        }
    }

    private fun checkCameraAndStart() {
        if (!isAdded || _binding == null) return

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (!isAdded || _binding == null) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            // 再次检查 fragment 是否仍活跃
            if (!isAdded || _binding == null) {
                Log.w(TAG, "Fragment detached before camera init completes")
                return@addListener
            }

            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetRotation(binding.viewFinder.display.rotation)
                    .build()

                cameraProvider?.unbindAll()

                // 根据用户选择切换前后摄像头
                val selectedCamera = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                // 检查目标摄像头是否可用，不可用则回退到另一个
                val availableCameras = cameraProvider?.availableCameraInfos ?: emptyList()
                val targetLens = if (useFrontCamera) CameraSelector.LENS_FACING_FRONT
                    else CameraSelector.LENS_FACING_BACK
                val hasTargetCamera = availableCameras.any {
                    it.lensFacing == targetLens
                }
                val finalSelector = if (hasTargetCamera) selectedCamera else {
                    Log.w(TAG, "Target camera not available, falling back")
                    if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                }

                cameraProvider?.bindToLifecycle(
                    viewLifecycleOwner,
                    finalSelector,
                    preview,
                    imageCapture
                )

                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed: ${e.message}")
                // 相机不可用不影响用户进入补全页
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (!isAdded || _binding == null) return

        binding.captureButton.isEnabled = false

        val photoFile = File(requireContext().cacheDir, "drug_${System.currentTimeMillis()}.jpg")

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (!isAdded) return
                    val activity = requireActivity() as DrugEntryActivity

                    // 保存照片 URI 到 session
                    activity.addPhoto(android.net.Uri.fromFile(photoFile))

                    // 设置待裁剪照片路径 → 跳转到裁剪页
                    activity.updateSession { it.copy(pendingPhotoPath = photoFile.absolutePath) }

                    activity.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        binding.captureButton.isEnabled = true
                        activity.navigateTo(DrugEntryStep.CROP)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    if (isAdded && _binding != null) {
                        binding.captureButton.isEnabled = true
                    }
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraProvider?.unbindAll()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CaptureGuideFragment"
    }
}
