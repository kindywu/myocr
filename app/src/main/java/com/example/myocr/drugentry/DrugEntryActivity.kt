package com.example.myocr.drugentry

import android.content.Context
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.myocr.R
import com.example.myocr.databinding.ActivityDrugEntryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 药品录入流程宿主 Activity
 *
 * 使用 FragmentManager 管理 6 个页面的导航。
 * 持有 DrugEntrySession 作为跨 Fragment 的共享状态。
 */
class DrugEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDrugEntryBinding

    /** 跨页面共享的采集会话状态 */
    var session: DrugEntrySession = DrugEntrySession()
        private set

    /** 更新 session（允许 Fragment 修改内部字段） */
    fun updateSession(transform: (DrugEntrySession) -> DrugEntrySession) {
        session = transform(session)
    }

    /** 正则 OCR 解析器（兜底） */
    val drugOcrParser: DrugOcrParser = DrugOcrParser()

    /** DeepSeek API 客户端（由 API key 按需创建） */
    private var deepSeekClient: DeepSeekClient? = null

    /** LLM 提示词模板管理器 */
    val promptManager: PromptManager by lazy { PromptManager(this) }

    companion object {
        private const val PREFS_NAME = "drug_entry_prefs"
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
    }

    // ==================== DeepSeek API Key & LLM 配置 ====================

    /**
     * 从配置文件读取 LLM 配置属性
     */
    private val llmConfig: java.util.Properties by lazy {
        java.util.Properties().apply {
            try {
                assets.open("llm_config.properties").use { input ->
                    load(input)
                }
            } catch (e: Exception) {
                android.util.Log.w("DrugEntryActivity", "llm_config.properties not found in assets", e)
            }
        }
    }

    /**
     * 获取 DeepSeek API key
     *
     * 优先返回 SharedPreferences 中的值（用户通过 UI 设置），
     * 为空时回退到 assets/llm_config.properties
     */
    fun getDeepSeekApiKey(): String {
        val prefsKey = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEEPSEEK_API_KEY, "") ?: ""
        if (prefsKey.isNotBlank()) return prefsKey
        return llmConfig.getProperty("deepseek_api_key", "")
    }

    /**
     * 获取 LLM 模型名称
     */
    fun getLlmModel(): String {
        return llmConfig.getProperty("deepseek_model", "deepseek-v4-flash")
    }

    /**
     * 获取 LLM API URL
     */
    fun getLlmApiUrl(): String {
        return llmConfig.getProperty("deepseek_api_url", "https://api.deepseek.com/chat/completions")
    }

    /**
     * 获取 LLM 请求超时（毫秒）
     */
    fun getLlmTimeoutMs(): Int {
        return llmConfig.getProperty("deepseek_timeout_ms", "30000").toIntOrNull() ?: 30000
    }

    /**
     * 保存 DeepSeek API key
     */
    fun saveDeepSeekApiKey(key: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEEPSEEK_API_KEY, key)
            .apply()
        // 重置客户端，下次按需创建
        deepSeekClient = null
    }

    /**
     * 获取 DeepSeek 客户端（有 key 时才创建）
     */
    fun getDeepSeekClient(): DeepSeekClient? {
        val key = getDeepSeekApiKey()
        if (key.isBlank()) return null
        if (deepSeekClient == null) {
            deepSeekClient = DeepSeekClient(
                apiKey = key,
                promptManager = promptManager,
                config = DeepSeekClient.LlmConfig(
                    apiUrl = getLlmApiUrl(),
                    model = getLlmModel(),
                    timeoutMs = getLlmTimeoutMs()
                )
            )
        }
        return deepSeekClient
    }

    /**
     * 弹出 API Key 设置对话框
     */
    fun showApiKeyDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(getDeepSeekApiKey())
            hint = "sk-..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("DeepSeek API 设置")
            .setMessage("输入 API key 后启用 LLM 增强识别，可大幅提升药品字段提取准确性。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text?.toString()?.trim() ?: ""
                saveDeepSeekApiKey(key)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDrugEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 注册系统返回键处理（使用 OnBackPressedDispatcher 替代已废弃的 onBackPressed）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 1) {
                    // 更新 session 的 currentStep
                    val entryCount = supportFragmentManager.backStackEntryCount
                    val prevEntry = supportFragmentManager.getBackStackEntryAt(entryCount - 2)
                    val prevStep = DrugEntryStep.entries.find { it.name == prevEntry.name }
                    if (prevStep != null) {
                        session = session.copy(currentStep = prevStep)
                    }
                    supportFragmentManager.popBackStack()
                } else {
                    finish()
                }
            }
        })

        // 启动到录入方式选择页
        if (savedInstanceState == null) {
            navigateTo(DrugEntryStep.ENTRY)
        }
    }

    // ==================== 导航 ====================

    /**
     * 导航到指定步骤
     */
    fun navigateTo(step: DrugEntryStep) {
        session = session.copy(currentStep = step)
        val fragment = createFragmentForStep(step)
        val tag = step.name

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right, R.anim.slide_out_left,
                R.anim.slide_in_left, R.anim.slide_out_right
            )
            .replace(R.id.fragmentContainer, fragment, tag)
            .addToBackStack(tag)
            .commit()
    }

    private fun createFragmentForStep(step: DrugEntryStep): Fragment {
        return when (step) {
            DrugEntryStep.ENTRY -> EntryFragment()
            DrugEntryStep.CAPTURE -> CaptureGuideFragment()
            DrugEntryStep.CROP -> CropFragment()
            DrugEntryStep.COMPLETION -> CaptureCompleteFragment()
            DrugEntryStep.CONFIRM -> ConfirmFragment()
            DrugEntryStep.MANUAL -> ManualEntryFragment()
            DrugEntryStep.SAVED -> SavedFragment()
        }
    }

    // ==================== 状态更新 ====================

    /**
     * 添加已拍摄照片
     */
    fun addPhoto(photoUri: android.net.Uri) {
        session = session.copy(
            capturedPhotos = session.capturedPhotos + photoUri,
            captureRound = session.captureRound + 1
        )
    }

    /**
     * 设置录入来源
     */
    fun setOrigin(origin: EntryOrigin) {
        session = session.copy(origin = origin)
    }

    /**
     * 设置重拍目标字段
     *
     * @param fieldKey 目标字段 key（如 "drugName"），或 null 表示全字段采集
     * @param source 重拍来源步骤，拍完后跳回该页面
     */
    fun startRetake(fieldKey: String?, source: DrugEntryStep) {
        session = session.copy(
            retakeFieldTarget = fieldKey,
            retakeSource = source
        )
        navigateTo(DrugEntryStep.CAPTURE)
    }

    /**
     * 获取当前是否处于重拍模式
     */
    fun isRetakeMode(): Boolean = session.retakeFieldTarget != null

    /**
     * 获取重拍来源
     */
    fun getRetakeSource(): DrugEntryStep? = session.retakeSource

    /**
     * 清理重拍标记
     */
    fun clearRetake() {
        session = session.copy(retakeFieldTarget = null, retakeSource = null)
    }

    /**
     * 更新药品信息（支持字段级更新）
     *
     * - 如果是字段级重拍（retakeFieldTarget 有值），只更新目标字段
     * - 否则全字段更新（首次采集或全字段重拍）
     */
    fun updateDrugInfo(newInfo: DrugInfo, source: FieldStatus) {
        val targetField = session.retakeFieldTarget

        if (targetField != null) {
            // 字段级更新：只更新目标字段
            val current = session.drugInfo
            val updated = when (targetField) {
                "drugName" -> current.copy(drugName = newInfo.drugName.ifBlank { current.drugName })
                "expiryDate" -> current.copy(expiryDate = newInfo.expiryDate.ifBlank { current.expiryDate })
                "manufacturer" -> current.copy(manufacturer = newInfo.manufacturer.ifBlank { current.manufacturer })
                "batchNumber" -> current.copy(batchNumber = newInfo.batchNumber.ifBlank { current.batchNumber })
                else -> current
            }

            val newStatuses = session.fieldStatuses.toMutableMap()
            val targetValue = when (targetField) {
                "drugName" -> updated.drugName
                "expiryDate" -> updated.expiryDate
                "manufacturer" -> updated.manufacturer
                "batchNumber" -> updated.batchNumber
                else -> ""
            }
            if (targetValue.isNotBlank()) newStatuses[targetField] = source

            session = session.copy(drugInfo = updated, fieldStatuses = newStatuses)
        } else {
            // 全字段更新
            val merged = session.drugInfo.mergeWith(newInfo)
            val newStatuses = session.fieldStatuses.toMutableMap()
            if (newInfo.drugName.isNotBlank()) newStatuses["drugName"] = source
            if (newInfo.expiryDate.isNotBlank()) newStatuses["expiryDate"] = source
            if (newInfo.manufacturer.isNotBlank()) newStatuses["manufacturer"] = source
            if (newInfo.batchNumber.isNotBlank()) newStatuses["batchNumber"] = source
            session = session.copy(drugInfo = merged, fieldStatuses = newStatuses)
        }
    }

    /**
     * 设置 LLM 多候选结果（传递给 CaptureCompleteFragment 展示供用户选择）
     */
    fun setLlmCandidates(candidates: Map<String, DeepSeekClient.FieldCandidates>) {
        session = session.copy(llmCandidates = candidates)
    }

    /**
     * 重置会话（用于"继续录入"）
     */
    fun resetSession() {
        session = DrugEntrySession()
        // 清除回退栈，回到入口
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        navigateTo(DrugEntryStep.ENTRY)
    }
}
