# 逐字段语音补充辅助 LLM 提取 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 CaptureCompleteFragment 中，点击字段值触发 LLM 重新提取时，增加可选语音补充入口。

**Architecture:** 修改 `startSingleFieldExtraction()` 流程，先弹「是否添加语音补充？」对话框，用户选择「是」后启动 STT，语音文本经确认后与 OCR 文本一起传给 LLM。通过 `fieldVoiceInputs` map 暂存语音文本，提取完成后清理。`DeepSeekClient` 参数名从 `voiceInputDrugName` 改为 `userVoiceText`。

**Tech Stack:** Kotlin, Android SpeechRecognizer, DeepSeekClient

---

## 文件变动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/src/main/java/com/example/myocr/drugentry/DeepSeekClient.kt` | 修改 | 参数名重命名 + KDoc 更新 |
| `app/src/main/java/com/example/myocr/drugentry/DrugEntrySession.kt` | 修改 | 新增 `fieldVoiceInputs` 字段 |
| `app/src/main/java/com/example/myocr/drugentry/CaptureCompleteFragment.kt` | 修改 | 核心业务逻辑 |
| `app/src/androidTest/java/com/example/myocr/OcrLlmComparisonTest.kt` | 修改 | 参数名同步更新 |

---

### Task 1: `DeepSeekClient` — 参数名重命名

**Files:**
- Modify: `app/src/main/java/com/example/myocr/drugentry/DeepSeekClient.kt`
- Modify: `app/src/androidTest/java/com/example/myocr/OcrLlmComparisonTest.kt`

将参数名从 `voiceInputDrugName` 改为 `userVoiceText`，语义更通用（不特指药品名称）。

- [ ] **Step 1: `DeepSeekClient.kt` — 替换 `extractDrugInfo()` 的参数名和 KDoc**

```kotlin
// 约第 569 行，KDoc
-     * @param voiceInputDrugName 用户语音输入的药品名称（辅助 LLM 判断）
+     * @param userVoiceText 用户语音补充文本（与 OCR 文本一起发给 LLM 参考）
```

```kotlin
// 约第 575 行，函数签名
-        voiceInputDrugName: String = ""
+        userVoiceText: String = ""
```

- [ ] **Step 2: `DeepSeekClient.kt` — 替换 `extractDrugInfo()` 方法体内的引用**

```kotlin
// 约第 584 行
-                formatPositionalUserMessage(ocrLines, voiceInputDrugName)
+                formatPositionalUserMessage(ocrLines, userVoiceText)
// 约第 587 行
-                formatSimpleUserMessage(filtered, voiceInputDrugName)
+                formatSimpleUserMessage(filtered, userVoiceText)
```

- [ ] **Step 3: `DeepSeekClient.kt` — 替换 `extractSingleField()` 的参数名和 KDoc**

```kotlin
// 约第 626 行，KDoc
-     * @param voiceInputDrugName 用户语音输入的药品名称（辅助 LLM 判断）
+     * @param userVoiceText 用户语音补充文本（与 OCR 文本一起发给 LLM 参考）
```

```kotlin
// 约第 633 行，函数签名
-        voiceInputDrugName: String = ""
+        userVoiceText: String = ""
```

- [ ] **Step 4: `DeepSeekClient.kt` — 替换 `extractSingleField()` 方法体内的引用**

```kotlin
// 约第 645 行
-                formatPositionalUserMessage(ocrLines, voiceInputDrugName, fieldLabel)
+                formatPositionalUserMessage(ocrLines, userVoiceText, fieldLabel)
// 约第 648 行
-                formatSimpleUserMessage(filtered, voiceInputDrugName, fieldLabel)
+                formatSimpleUserMessage(filtered, userVoiceText, fieldLabel)
```

- [ ] **Step 5: 同步修改测试文件**

```kotlin
// app/src/androidTest/.../OcrLlmComparisonTest.kt 约第 67、114 行
-                voiceInputDrugName = ""
+                userVoiceText = ""
```

- [ ] **Step 6: 编译确认**

```bash
cd app && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

---

### Task 2: `DrugEntrySession` — 新增 `fieldVoiceInputs` 字段

**Files:**
- Modify: `app/src/main/java/com/example/myocr/drugentry/DrugEntrySession.kt`

- [ ] **Step 1: 新增字段**

```kotlin
// DrugEntrySession data class 末尾（retakeSource 之后）
    /** 各字段用户已采纳的语音补充文本（用于 LLM 提取参考） */
    val fieldVoiceInputs: Map<String, String> = emptyMap(),
```

注意：末尾已有逗号的字段后面加逗号，新增字段末尾也要有逗号（因为是 data class）。

- [ ] **Step 2: 编译确认**

```bash
cd app && ./gradlew assembleDebug
```

---

### Task 3: `CaptureCompleteFragment` — 重构 `startSingleFieldExtraction()` 流程

**Files:**
- Modify: `app/src/main/java/com/example/myocr/drugentry/CaptureCompleteFragment.kt`

**改动概述：**
1. 在 `startSingleFieldExtraction()` 开头，先弹「是否添加语音补充？」对话框
2. 用户选「是」→ 启动 STT，带标记 `voiceSourceIsLlmExtraction = true` 
3. 用户选「否」→ 走原有 LLM 提取流程
4. `onVoiceResult()` 根据标记判断是 🎤 按钮路径还是 LLM 补充路径

- [ ] **Step 1: 新增成员变量 `voiceSourceIsLlmExtraction`**

```kotlin
// 在 voiceFieldKey 旁边（约第 46 行）
    /** 当前正在语音输入的字段 key */
    private var voiceFieldKey: String = ""

    /** 当前语音输入是否由 LLM 提取触发（false 为 🎤 按钮触发） */
    private var voiceSourceIsLlmExtraction: Boolean = false
```

- [ ] **Step 2: 修改 `startSingleFieldExtraction()` — 先弹「是否语音补充」对话框**

当前方法体（约第 499-573 行）全部替换为：

```kotlin
    /**
     * 启动单字段 LLM 提取（含可选语音补充）
     *
     * 先弹「是否添加语音补充？」对话框：
     * - 是 → 启动 STT，语音文本确认后与 OCR 文本一起发给 LLM
     * - 否 → 仅 OCR 文本，现有行为不变
     */
    private fun startSingleFieldExtraction(
        activity: DrugEntryActivity,
        fieldKey: String,
        valueView: View
    ) {
        val client = deepSeekClient ?: return
        val rawText = activity.session.rawOcrText
        if (rawText.isBlank()) {
            Toast.makeText(requireContext(), "无 OCR 文本，无法提取", Toast.LENGTH_SHORT).show()
            return
        }

        // 弹「是否添加语音补充」对话框
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("${getFieldLabel(fieldKey)} — LLM 重新提取")
            .setMessage("是否添加语音补充来辅助识别？")
            .setPositiveButton("是") { _, _ ->
                // 启动语音输入（标记为 LLM 提取路径）
                voiceSourceIsLlmExtraction = true
                startVoiceInput(fieldKey)
            }
            .setNegativeButton("否") { _, _ ->
                // 走原有 LLM 提取（无语音）
                doLlmExtraction(activity, fieldKey, valueView, client, rawText, "")
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 执行 LLM 提取并更新 UI
     *
     * @param voiceText 用户语音补充文本（可能为空）
     */
    private fun doLlmExtraction(
        activity: DrugEntryActivity,
        fieldKey: String,
        valueView: View,
        client: DeepSeekClient,
        rawText: String,
        voiceText: String
    ) {
        valueView.isClickable = false
        (valueView as? android.widget.TextView)?.text = "提取中…"

        llmExecutor.execute {
            try {
                val fc = client.extractSingleField(
                    fieldKey = fieldKey,
                    rawText = rawText,
                    userVoiceText = voiceText
                )
                val newValue = fc.bestValue

                activity.runOnUiThread {
                    if (!isAdded) return@runOnUiThread

                    if (newValue.isNotBlank()) {
                        // 只更新该字段
                        val current = activity.session.drugInfo
                        val updated = when (fieldKey) {
                            "drugName" -> current.copy(drugName = newValue)
                            "expiryDate" -> current.copy(expiryDate = newValue)
                            "manufacturer" -> current.copy(manufacturer = newValue)
                            "batchNumber" -> current.copy(batchNumber = newValue)
                            else -> return@runOnUiThread
                        }
                        activity.updateDrugInfo(updated, FieldStatus.RECOGNIZED)
                        // 刷新页面
                        updateFieldStatus(activity, activity.session)
                        updateProgress(activity.session)

                        Toast.makeText(requireContext(),
                            "${getFieldLabel(fieldKey)}: $newValue", Toast.LENGTH_SHORT).show()
                    } else {
                        (valueView as? android.widget.TextView)?.text = activity.session.drugInfo.run {
                            when (fieldKey) {
                                "drugName" -> drugName
                                "expiryDate" -> expiryDate
                                "manufacturer" -> manufacturer
                                "batchNumber" -> batchNumber
                                else -> ""
                            }
                        }
                        valueView.isClickable = deepSeekClient != null
                        Toast.makeText(requireContext(),
                            "LLM 无法提取该字段", Toast.LENGTH_SHORT).show()
                    }

                    // 清理该字段的语言文本
                    activity.updateSession { session ->
                        session.copy(
                            fieldVoiceInputs = session.fieldVoiceInputs - fieldKey
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Single-field extraction [$fieldKey] failed", e)
                activity.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    (valueView as? android.widget.TextView)?.text = activity.session.drugInfo.run {
                        when (fieldKey) {
                            "drugName" -> drugName
                            "expiryDate" -> expiryDate
                            "manufacturer" -> manufacturer
                            "batchNumber" -> batchNumber
                            else -> ""
                        }
                    }
                    valueView.isClickable = deepSeekClient != null
                    Toast.makeText(requireContext(),
                        "提取失败: ${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
```

- [ ] **Step 3: 修改 `onVoiceResult()` — 区分 LLM 路径与 🎤 路径**

当前 `onVoiceResult()`（约第 344-372 行）修改为：

```kotlin
    /** 语音识别成功后的处理 */
    private fun onVoiceResult(fieldKey: String, spokenText: String) {
        if (voiceSourceIsLlmExtraction) {
            // LLM 提取路径：弹出确认对话框让用户决定采纳还是放弃
            showVoiceConfirmForLlm(fieldKey, spokenText)
        } else {
            // 🎤 按钮路径：保持现有行为不变
            handleVoiceDirectReplace(fieldKey, spokenText)
        }
    }

    /** 🎤 按钮路径：现有行为（直接替换/对比确认），不走 LLM */
    private fun handleVoiceDirectReplace(fieldKey: String, spokenText: String) {
        val activity = requireActivity() as DrugEntryActivity
        val current = activity.session.drugInfo

        val currentValue: String = when (fieldKey) {
            "drugName" -> current.drugName
            "expiryDate" -> current.expiryDate
            "manufacturer" -> current.manufacturer
            "batchNumber" -> current.batchNumber
            else -> ""
        }

        if (currentValue.isBlank()) {
            applyVoiceResult(activity, fieldKey, spokenText)
            return
        }

        if (spokenText == currentValue) {
            Toast.makeText(requireContext(),
                "语音识别结果与 OCR 一致", Toast.LENGTH_SHORT).show()
            return
        }

        showVoiceConfirmDialog(activity, fieldKey, currentValue, spokenText)
    }

    /** LLM 路径：显示语音文本确认对话框 */
    private fun showVoiceConfirmForLlm(fieldKey: String, spokenText: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音识别结果 — ${getFieldLabel(fieldKey)}")
            .setMessage(spokenText)
            .setPositiveButton("采纳") { _, _ ->
                val activity = requireActivity() as DrugEntryActivity
                // 存入语音文本
                activity.updateSession { session ->
                    session.copy(
                        fieldVoiceInputs = session.fieldVoiceInputs + (fieldKey to spokenText)
                    )
                }
                // 执行 LLM 提取（带语音文本）
                val voiceText = activity.session.fieldVoiceInputs[fieldKey] ?: ""
                val client = deepSeekClient ?: return@setPositiveButton
                val rawText = activity.session.rawOcrText
                val valueView = findValueView(fieldKey) ?: return@setPositiveButton
                doLlmExtraction(activity, fieldKey, valueView, client, rawText, voiceText)
            }
            .setNegativeButton("放弃") { _, _ ->
                // 走 LLM 提取（无语音文本）
                val activity = requireActivity() as DrugEntryActivity
                val client = deepSeekClient ?: return@setNegativeButton
                val rawText = activity.session.rawOcrText
                val valueView = findValueView(fieldKey) ?: return@setNegativeButton
                doLlmExtraction(activity, fieldKey, valueView, client, rawText, "")
            }
            .setCancelable(false)
            .show()
    }

    /** 根据 fieldKey 找到对应的字段值 View */
    private fun findValueView(fieldKey: String): View? {
        return when (fieldKey) {
            "drugName" -> binding.drugNameValue
            "expiryDate" -> binding.expiryValue
            "manufacturer" -> binding.manufacturerValue
            "batchNumber" -> binding.batchValue
            else -> null
        }
    }
```

- [ ] **Step 4: 在 `startVoiceInput()` 中重置标记**

```kotlin
    /** 启动系统语音识别 —— 自动请求权限后使用 SpeechRecognizer + 自定义动画 */
    private fun startVoiceInput(fieldKey: String) {
        voiceFieldKey = fieldKey
+       // 重置 LLM 提取标记（默认走 🎤 路径；LLM 路径在调用前会设为 true）
+       // 注意：此方法被 🎤 按钮和 startSingleFieldExtraction 两处调用，
+       // 由调用方在调用前设置 voiceSourceIsLlmExtraction
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        doStartVoiceInput(fieldKey)
    }
```

不需要重置标记——`startSingleFieldExtraction` 会在调用 `startVoiceInput` 之前设置 `voiceSourceIsLlmExtraction = true`，而 🎤 按钮是在字段上绑定的（不会经过 `startSingleFieldExtraction`）。但如果用户连续两次不同来源触发，需要保证状态正确。

更稳妥：在 `startVoiceInput()` 入口处不重置标记，由调用方负责设置。

🎤 按钮调用路径（约第 282-285 行，无需修改）：

```kotlin
        binding.drugNameVoice.setOnClickListener { startVoiceInput("drugName") }
        // ...
```

这里 voiceSourceIsLlmExtraction 默认是 false（成员变量初始化），所以走 🎤 路径。

- [ ] **Step 5: 编译确认**

```bash
cd app && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

---

### Task 4: 端到端验证

**Files:**（无需修改）

- [ ] **Step 1: 编译 debug 包**

```bash
cd app && ./gradlew assembleDebug
```

- [ ] **Step 2: 手动测试流程**

在真机上测试以下场景：

| # | 场景 | 操作 | 预期 |
|---|------|------|------|
| 1 | 🎤 按钮—直接填字段 | 点字段旁的 🎤 按钮说话 | 语音文本直接替换字段值，不走 LLM |
| 2 | 🎤 按钮—空字段 | 空字段点 🎤 说话 | 语音文本直接填入 |
| 3 | 点击字段值—否 | 点字段值 → 「是否语音补充」→「否」 | LLM(仅 OCR) 提取 |
| 4 | 点击字段值—语音→采纳 | 点字段值 → 「是」→ 说话 → 「采纳」 | LLM(OCR + 语音) 提取 |
| 5 | 点击字段值—语音→放弃 | 点字段值 → 「是」→ 说话 → 「放弃」 | LLM(仅 OCR) 提取 |
| 6 | 多次点击不同字段 | 先操作药品名称，再操作有效期至 | 各自独立，互不影响 |

---

### Task 5: 提交

- [ ] **Step 1: Commit**

```bash
git add -A
git commit -m "feat: 逐字段 LLM 提取时支持可选语音补充

- DeepSeekClient 参数名 voiceInputDrugName → userVoiceText
- DrugEntrySession 新增 fieldVoiceInputs 暂存语音文本
- 点击字段值触发 LLM 前弹「是否添加语音补充」对话框
- 语音文本确认后与 OCR 一起发给 LLM，提取完成后清理
"
```
