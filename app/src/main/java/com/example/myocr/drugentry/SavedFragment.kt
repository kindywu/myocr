package com.example.myocr.drugentry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.myocr.R

/**
 * 保存完成页
 *
 * 展示成功状态和已保存的数据摘要。
 * 提供"继续录入"和"完成"两个操作。
 */
class SavedFragment : Fragment() {

    private var _binding: com.example.myocr.databinding.FragmentSavedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = com.example.myocr.databinding.FragmentSavedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as DrugEntryActivity
        val info = activity.session.drugInfo

        // 填充摘要
        binding.savedDrugName.text = info.drugName.ifBlank { "-" }
        binding.savedExpiry.text = info.expiryDate.ifBlank { "-" }
        binding.savedManufacturer.text = info.manufacturer.ifBlank { "-" }
        binding.savedBatch.text = info.batchNumber.ifBlank { "-" }

        // 继续录入 → 重置会话，回到入口
        binding.continueButton.setOnClickListener {
            activity.resetSession()
        }

        // 完成 → 关闭 Activity
        binding.doneButton.setOnClickListener {
            activity.finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
