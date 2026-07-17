package com.example.myocr.drugentry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.myocr.R

/**
 * 录入方式选择页
 *
 * 提供两个入口：
 * - 拍照识别（OCR 主路径）
 * - 手动填写（兜底路径）
 *
 * 右上角设置按钮可配置 DeepSeek API key 启用 LLM 增强。
 */
class EntryFragment : Fragment() {

    private var _binding: com.example.myocr.databinding.FragmentEntryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = com.example.myocr.databinding.FragmentEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as DrugEntryActivity

        // 拍照识别 → 进入拍照采集
        binding.scanCard.setOnClickListener {
            activity.setOrigin(EntryOrigin.OCR)
            activity.navigateTo(DrugEntryStep.CAPTURE)
        }

        // 手动填写 → 进入手动录入
        binding.manualCard.setOnClickListener {
            activity.setOrigin(EntryOrigin.MANUAL)
            activity.navigateTo(DrugEntryStep.MANUAL)
        }

        // 通用文字识别 → 启动通用 OCR 扫描器
        binding.generalScanRow.setOnClickListener {
            val intent = android.content.Intent(activity, com.example.myocr.MainActivity::class.java)
            startActivity(intent)
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
