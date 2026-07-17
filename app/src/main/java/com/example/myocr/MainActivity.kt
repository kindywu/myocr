package com.example.myocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.myocr.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import com.example.myocr.drugentry.DrugEntryActivity
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // CameraX
    private var imageCapture: ImageCapture? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null

    // OCR
    private var ocrEngine: OcrEngine? = null
    private var isOcrReady = false

    // 后台线程池
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 上传相关
    private var currentOcrText: String = ""
    private val PREFS_NAME = "myocr_prefs"
    private val KEY_SERVER_URL = "server_url"

    // TTS
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false

    // 权限
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera() else showPermissionDenied()
    }

    // 相册选择
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onGalleryImagePicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initUploadViews()
        initTts()
        initOcrEngine()
        checkCameraPermissionAndStart()
    }

    private fun initViews() {
        binding.captureButton.setOnClickListener { takePhoto() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.galleryButton.setOnClickListener { pickFromGallery() }
        binding.copyButton.setOnClickListener { copyResultText() }
        binding.uploadButton.setOnClickListener { uploadResult() }
        binding.speakButton.setOnClickListener { toggleSpeak() }
        binding.resultCard.visibility = View.GONE
        binding.progressContainer.visibility = View.GONE
        binding.serverCard.visibility = View.VISIBLE

        // 启用 Toolbar 菜单
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            onMenuItemClick(menuItem)
        }
    }

    // ==================== 菜单 ====================

    private fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_drug_entry -> {
                startActivity(android.content.Intent(this, DrugEntryActivity::class.java))
                true
            }
            else -> false
        }
    }

    // ==================== 上传设置 ====================

    private fun initUploadViews() {
        // 恢复保存的服务器地址
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, "")
        if (savedUrl != null && savedUrl.isNotEmpty()) {
            binding.serverUrlInput.setText(savedUrl)
        }

        // 自动保存服务器地址
        binding.serverUrlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString(KEY_SERVER_URL, s?.toString() ?: "").apply()
            }
        })
    }

    // ==================== TTS 语音朗读 ====================

    private fun initTts() {
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.CHINESE)
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                isTtsReady = true

                val engine = tts?.defaultEngine ?: "null"
                val langAvail = tts?.isLanguageAvailable(Locale.CHINESE) ?: -99
                Log.d(TAG, "TTS ready: engine=$engine, chinese=$langAvail")
            } else {
                Log.w(TAG, "TTS init status=$status")
                isTtsReady = true
            }
        }
    }

    /**
     * 用 Intent 方式调用系统 TTS（兼容不注册标准引擎的 ROM）
     */
    /**
     * 用 Intent 调用系统 TTS（兼容不注册标准引擎的 ROM，如小米 HyperOS）
     */
    private fun speakViaIntent(text: String) {
        try {
            // 用字符串常量代替 Engine.* 避免编译问题
            val intent = android.content.Intent("android.intent.action.SPEAK").apply {
                putExtra("android.intent.extra.TEXT", text)
                putExtra("android.intent.extra.UTTERANCE_ID", "ocr_utterance")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 如果上面的 Intent 没有应用处理，尝试用 VIEW 动作包裹文本
            val resolved = packageManager.resolveActivity(intent, 0)
            if (resolved != null) {
                startActivity(intent)
                Log.d(TAG, "Intent TTS 启动成功")
            } else {
                Log.w(TAG, "SPEAK intent 无处理器，尝试辅助功能 TTS")
                // 兜底：用辅助功能设置页面引导
                runOnUiThread {
                    showTtsErrorDialog(
                        "朗读失败",
                        "系统 TTS 暂不可用。\n\n" +
                        "请确认：「设置 → 更多设置 → 无障碍 → 文字转语音」\n" +
                        "1. 首选引擎已选择\n" +
                        "2. 中文语音数据已下载\n" +
                        "3. 当前引擎能正常发音"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Intent TTS 失败", e)
            runOnUiThread {
                showTtsErrorDialog(
                    "朗读失败",
                    "无法通过系统 TTS 朗读。\n\n错误: ${e.message}"
                )
            }
        }
    }

    private fun showTtsErrorDialog(title: String, detail: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(detail)
            .setPositiveButton("TTS 设置") { _, _ -> openTtsSettings() }
            .setNegativeButton("安装语音数据") { _, _ -> installTtsData() }
            .setNeutralButton("关闭") { _, _ -> }
            .show()
    }

    private fun toggleSpeak() {
        if (!isTtsReady) {
            showTtsErrorDialog(
                "语音未就绪",
                "TTS 引擎还未初始化完成，请稍后重试。\n\n也可以去 TTS 设置中检查默认引擎。"
            )
            return
        }

        val text = binding.ocrResultText.text.toString()
        if (text.isEmpty()) return

        if (isSpeaking) {
            stopSpeaking()
        } else {
            startSpeaking(text)
        }
    }

    private fun openTtsSettings() {
        startActivity(android.content.Intent().apply {
            action = "com.android.settings.TTS_SETTINGS"
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun installTtsData() {
        try {
            startActivity(android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "无法打开语音数据安装", e)
            showTtsErrorDialog("无法安装", "无法启动语音数据安装，请手动到 TTS 设置中安装。\n\n错误: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isTtsReady) {
            tts?.shutdown()
            initTts()
        }
    }

    private fun startSpeaking(text: String) {
        // 先检查引擎
        val engine = tts?.defaultEngine
        val langAvail = tts?.isLanguageAvailable(Locale.CHINESE) ?: -99

        if (engine == null || langAvail == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 标准 TTS API 不支持中文 → 切到 Intent 方式
            Log.w(TAG, "标准 TTS 不支持中文(engine=$engine, lang=$langAvail)，使用 Intent 方式")
            speakViaIntent(text)
            return
        }

        // 标准方式
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                runOnUiThread { binding.speakButton.text = getString(R.string.stop) }
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                runOnUiThread { binding.speakButton.text = getString(R.string.speak) }
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                runOnUiThread {
                    binding.speakButton.text = getString(R.string.speak)
                    showTtsErrorDialog("朗读失败", "TTS 引擎无法朗读这段文字。\n\n原因可能是文字包含特殊字符或引擎不支持。")
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                isSpeaking = false
                runOnUiThread { binding.speakButton.text = getString(R.string.speak) }
            }
        })

        Log.d(TAG, "标准 TTS speak() 调用, engine=$engine")
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ocr_utterance")
        Log.d(TAG, "speak() 返回值: $result")
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "标准 TTS 失败，回退到 Intent 方式")
            speakViaIntent(text)
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
        binding.speakButton.text = getString(R.string.speak)
    }

    // ==================== OCR 初始化 ====================

    private fun initOcrEngine() {
        ocrExecutor.execute {
            try {
                ocrEngine = OcrEngine.create(this@MainActivity)
                isOcrReady = true
                Log.d(TAG, "OCR engine ready (PP-OCRv6 ONNX Small)")
            } catch (e: Exception) {
                Log.e(TAG, "OCR init failed", e)
                runOnUiThread {
                    Snackbar.make(binding.root, R.string.ocr_init_failed, Snackbar.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    // ==================== 相机权限 ====================

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionDenied() {
        Snackbar.make(binding.root, R.string.camera_permission_denied, Snackbar.LENGTH_INDEFINITE)
            .setAction("设置") {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }.show()
    }

    // ==================== CameraX ====================

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .build()

            cameraProvider?.unbindAll()
            try {
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    // ==================== 拍照 ====================

    private fun takePhoto() {
        val capture = imageCapture ?: return

        if (!isOcrReady) {
            Snackbar.make(binding.root, R.string.ocr_init, Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.captureButton.isEnabled = false
        binding.progressContainer.visibility = View.VISIBLE
        binding.resultCard.visibility = View.GONE

        val photoFile = File(cacheDir, "ocr_${System.currentTimeMillis()}.jpg")

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 在 OCR 线程处理识别
                    ocrExecutor.execute {
                        processOcr(photoFile)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    runOnUiThread {
                        Snackbar.make(binding.root, "拍照失败: ${exception.message}", Snackbar.LENGTH_LONG)
                            .show()
                        binding.progressContainer.visibility = View.GONE
                        binding.captureButton.isEnabled = true
                    }
                }
            }
        )
    }

    // ==================== 从相册选择 ====================

    private fun pickFromGallery() {
        if (!isOcrReady) {
            Snackbar.make(binding.root, R.string.ocr_init, Snackbar.LENGTH_SHORT).show()
            return
        }

        // 用系统相册选择器，无需额外权限
        galleryLauncher.launch("image/*")
    }

    private fun onGalleryImagePicked(uri: Uri) {
        // 显示加载状态
        binding.progressContainer.visibility = View.VISIBLE
        binding.resultCard.visibility = View.GONE
        binding.galleryButton.isEnabled = false

        // 后台线程处理
        ocrExecutor.execute {
            try {
                // 将 URI 内容复制到临时文件
                val tempFile = File(cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
                copyUriToFile(uri, tempFile)

                // 复用现有的 OCR 处理流程
                processOcr(tempFile)

                // processOcr 会在 finally 中删除文件，所以不用手动清理
            } catch (e: Exception) {
                Log.e(TAG, "处理相册图片失败", e)
                runOnUiThread {
                    Snackbar.make(binding.root, R.string.ocr_error, Snackbar.LENGTH_LONG).show()
                    binding.progressContainer.visibility = View.GONE
                    binding.galleryButton.isEnabled = true
                }
            }
        }
    }

    /**
     * 将 content:// URI 的内容复制到目标文件
     */
    private fun copyUriToFile(uri: Uri, destFile: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw java.io.IOException("无法打开 URI: $uri")
    }

    // ==================== OCR 处理（后台线程） ====================

    private fun processOcr(photoFile: File) {
        try {
            // 加载图片
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)

            // ML Kit 捆绑模式自动处理方向和图像预处理，直接识别
            val text = if (bitmap != null) {
                ocrEngine?.recognize(bitmap) ?: ""
            } else {
                ""
            }

            // 回到主线程更新 UI
            runOnUiThread {
                if (text.isEmpty()) {
                    binding.ocrResultText.text = getString(R.string.no_text_found)
                    binding.speakButton.visibility = View.GONE
                    binding.uploadButton.visibility = View.GONE
                } else {
                    binding.ocrResultText.text = text
                    currentOcrText = text
                    binding.speakButton.text = getString(R.string.speak)
                    binding.speakButton.visibility = View.VISIBLE
                    binding.uploadButton.visibility = View.VISIBLE
                    binding.uploadStatusText.visibility = View.GONE
                    try {
                        binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    } catch (_: Exception) {}
                }
                binding.resultCard.visibility = View.VISIBLE
                binding.progressContainer.visibility = View.GONE
                binding.captureButton.isEnabled = true
                binding.galleryButton.isEnabled = true
            }

            bitmap?.recycle()
            Log.d(TAG, "OCR result: ${text.take(100)}")
        } catch (e: Exception) {
            Log.e(TAG, "OCR processing failed", e)
            runOnUiThread {
                Snackbar.make(binding.root, R.string.ocr_error, Snackbar.LENGTH_LONG).show()
                binding.progressContainer.visibility = View.GONE
                binding.captureButton.isEnabled = true
                binding.galleryButton.isEnabled = true
            }
        } finally {
            try { photoFile.delete() } catch (_: Exception) {}
        }
    }

    // ==================== 复制 ====================

    private fun copyResultText() {
        val text = binding.ocrResultText.text.toString()
        if (text.isEmpty()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
        Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
    }

    // ==================== 上传 ====================

    private fun uploadResult() {
        val text = currentOcrText
        if (text.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }

        val serverUrl = binding.serverUrlInput.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Snackbar.make(binding.root, "请先输入服务器地址", Snackbar.LENGTH_SHORT).show()
            binding.serverUrlInput.requestFocus()
            return
        }

        // 补全 URL
        val fullUrl = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl
        } else {
            "http://$serverUrl"
        }

        if (!OcrUploader.isValidUrl(fullUrl)) {
            Snackbar.make(binding.root, "服务器地址格式不正确", Snackbar.LENGTH_SHORT).show()
            return
        }

        // UI 状态
        binding.uploadButton.isEnabled = false
        binding.uploadButton.text = getString(R.string.uploading)
        binding.uploadStatusText.visibility = View.VISIBLE
        binding.uploadStatusText.text = getString(R.string.uploading)
        binding.uploadStatusText.setTextColor(0xFF888888.toInt())

        // 后台线程上传
        uploadExecutor.execute {
            val result = OcrUploader.upload(fullUrl, text)

            runOnUiThread {
                binding.uploadButton.isEnabled = true
                binding.uploadButton.text = getString(R.string.upload)
                binding.uploadStatusText.visibility = View.VISIBLE

                if (result.success) {
                    binding.uploadStatusText.text = getString(R.string.upload_ok)
                    binding.uploadStatusText.setTextColor(0xFF4CAF50.toInt())
                    Snackbar.make(binding.root, R.string.upload_ok, Snackbar.LENGTH_SHORT).show()
                } else {
                    binding.uploadStatusText.text = "${getString(R.string.upload_fail)}: ${result.message}"
                    binding.uploadStatusText.setTextColor(0xFFE53935.toInt())
                    Snackbar.make(binding.root, "${getString(R.string.upload_fail)}: ${result.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onPause() {
        super.onPause()
        // 切到后台时停止朗读
        if (isSpeaking) {
            stopSpeaking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ocrExecutor.shutdown()
        uploadExecutor.shutdown()
        ocrEngine?.close()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
