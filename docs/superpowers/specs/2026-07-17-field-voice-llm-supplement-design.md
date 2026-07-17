# 逐字段语音补充辅助 LLM 提取 — 设计文档

## 概述

在 CaptureCompleteFragment（多角度补全页）中，当用户点击字段值触发单字段 LLM 重新提取时，增加一个**可选语音补充入口**。用户可以选择说话补充该字段的信息，语音文本经确认后与 OCR 文本一起发送给 LLM，提升 LLM 提取准确性。

## 背景

现有流程：
1. CaptureGuideFragment 拍照 → CropFragment 裁剪 → OCR → LLM 全字段提取
2. CaptureCompleteFragment 展示各字段识别结果
3. 用户可点击字段值触发 `extractSingleField()` 让 LLM 重新提取（改善结果）
4. 用户也可点 🎤 按钮直接说话替换字段值（不走 LLM）

不足：点击字段值触发的 LLM 提取只有 OCR 文本，缺少用户口述信息作为参考。

## 目标

- 点击字段值触发 LLM 提取时，用户可**可选**地添加语音补充
- 语音文本经确认采纳后，与 OCR 文本一起发给 LLM
- 不修改现有 🎤 按钮的行为
- 最小化改动，不新增布局文件

## 流程

```
用户点击字段值文本（如「药品名称」）

  ↓
AlertDialog: "是否添加语音补充？"
  ├─ [否] → LLM(仅 OCR) → 更新字段值（现有行为不变）
  └─ [是] → 启动 SpeechRecognizer（复用 dialog_voice_input.xml）
              ↓
             STT 完成 → 收到识别文本
              ↓
             AlertDialog: 显示语音文本，[采纳] / [放弃]
              ├─ [采纳] → 存入 session.fieldVoiceInputs
              │         → LLM(OCR + 语音文本) → 更新字段值
              │         → 清空 fieldVoiceInputs
              └─ [放弃] → LLM(仅 OCR) → 更新字段值
```

## 关键实现细节

现有的 `startVoiceInput()` / `onVoiceResult()` 被 🎤 按钮和新的 LLM 语音补充共用，需要区分触发来源：

- 新增一个**触发来源标记**（如 `voiceSourceIsLlmExtraction: Boolean`），在 `startVoiceInput()` 前设置
- 🎤 按钮 → `voiceSourceIsLlmExtraction = false` → 走现有行为（直接替换）
- 点击字段值 → `voiceSourceIsLlmExtraction = true` → 采纳后存入 `fieldVoiceInputs`，然后调 LLM

## 改动清单

### 1. `DrugEntrySession`

新增字段：

```kotlin
/** 各字段用户已采纳的语音补充文本（用于 LLM 参考） */
val fieldVoiceInputs: Map<String, String> = emptyMap()
```

- key: fieldKey（"drugName", "expiryDate", "manufacturer", "batchNumber"）
- value: 用户确认采纳的语音文本
- 提取完成后清空（置 emptyMap 或删除该 key）

### 2. `CaptureCompleteFragment.startSingleFieldExtraction()`

**当前行为（不修改）：**
- 立即设置 valueView.text = "提取中…"
- 不可点击
- 调 `extractSingleField(fieldKey, rawText)`，无语音

**新行为：**
- 先弹 AlertDialog：「是否添加语音补充？」（[是] / [否]）
  - **[否]**：走原有 LLM 提取（无语音），行为不变
  - **[是]**：启动 `startVoiceInput(fieldKey)`，复用现有 STT 逻辑

### 3. `CaptureCompleteFragment.onVoiceResult()`

调整逻辑：

- 对话被采纳时：除了 `applyVoiceResult()`，额外将语音文本存入 `session.fieldVoiceInputs[fieldKey]`
- 然后调 `startSingleFieldExtraction()` 继续执行 LLM 提取（此时 session 中有语音文本）

### 4. `DeepSeekClient` — 参数名重命名

当前 `voiceInputDrugName` 命名有误导性（暗示只能传药品名称），改为 `userVoiceText`：

- `extractSingleField()` 的 `voiceInputDrugName: String` → `userVoiceText: String`
- `extractDrugInfo()` 的 `voiceInputDrugName: String` → `userVoiceText: String`
- 所有格式化方法中的对应参数同步改名

### 5. LLM 调用传递语音文本

`startSingleFieldExtraction()` 的后段（`llmExecutor.execute` 中的调用），从 session 取出该字段的语音文本传进去：

```kotlin
// 从 session 取该字段的语音文本
val voiceText = activity.session.fieldVoiceInputs[fieldKey] ?: ""
val fc = client.extractSingleField(
    fieldKey = fieldKey,
    rawText = rawText,
    userVoiceText = voiceText
)
```

### 5. 提取完成后清理

```kotlin
// 清除该字段的语音文本
activity.updateSession { session ->
    session.copy(
        fieldVoiceInputs = session.fieldVoiceInputs - fieldKey
    )
}
```

## 不需要改的

- 🎤 按钮行为：不变，仍直接替换字段值（不走 LLM）
- `DeepSeekClient` API 行为不变，仅参数名 `voiceInputDrugName` → `userVoiceText`
- 布局文件：不需要新增
- 语音动画对话框：复用现有 `dialog_voice_input.xml`
- 语音确认对话框：复用现有确认逻辑（AcceptDialog）

## 边界情况

| 场景 | 行为 |
|------|------|
| 用户无语音输入（直接点否） | LLM 仅 OCR 文本，与现有行为一致 |
| STT 无匹配结果 | Toast 提示，回退到仅 OCR 文本 |
| 语音文本与 OCR 值完全一致 | 仍传给 LLM（不影响结果） |
| 多次添加语音 | 每次覆盖该字段的 fieldVoiceInputs |
| 用户连续点击不同字段 | 每个字段独立存储，互不影响 |

## 不变的行为

- `extractSingleField()` 在失败时回退到原字段值
- UI 状态恢复逻辑不变
- LLM 异常时 show Toast + 恢复值文本
