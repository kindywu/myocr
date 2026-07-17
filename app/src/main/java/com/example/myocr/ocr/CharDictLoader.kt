package com.example.myocr.ocr

import android.content.Context

/**
 * 从 rec/inference.yml 加载 character_dict（模型实际使用的字符集）
 *
 * index 0 保留给 CTC blank
 */
object CharDictLoader {

    fun load(context: Context, assetPath: String = "model/ppocrv6_onnx/small_rec/inference.yml"): List<String> {
        val yaml = context.assets.open(assetPath)
            .bufferedReader()
            .readText()
        return parseCharacterDict(yaml)
    }

    private fun parseCharacterDict(yaml: String): List<String> {
        val chars = mutableListOf("") // index 0 = CTC blank
        var inDict = false

        for (line in yaml.lines()) {
            val trimmed = line.trimStart()

            if (trimmed == "character_dict:") {
                inDict = true
                continue
            }

            if (!inDict) continue

            // 一旦遇到非 "- " 开头的行，说明 character_dict 结束了
            if (!trimmed.startsWith("- ")) break

            val raw = trimmed.removePrefix("- ").trim()

            // 去掉单引号或双引号
            val ch = when {
                raw.startsWith("'") && raw.endsWith("'") && raw.length >= 2 ->
                    raw.substring(1, raw.length - 1)
                raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
                    raw.substring(1, raw.length - 1)
                else -> raw
            }

            chars.add(ch)
        }

        return chars
    }
}
