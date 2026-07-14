package com.example.myocr.drugentry

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.myocr.R
import java.util.Calendar

/**
 * 手动录入页
 *
 * 兜底路径：用户逐项填写药品信息。
 * 有效期字段点击弹出日期选择器。
 * 提供"去拍照补全"入口，可从手动切换到拍照流程。
 */
class ManualEntryFragment : Fragment() {

    private var _binding: com.example.myocr.databinding.FragmentManualEntryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = com.example.myocr.databinding.FragmentManualEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as DrugEntryActivity

        // 返回
        binding.backButton.setOnClickListener { activity.supportFragmentManager.popBackStack() }

        // 有效期 → 点击弹出日期选择器
        setupDatePicker()

        // 去拍照补全 → 切换到拍照流程（保留已填字段）
        binding.goCaptureCard.setOnClickListener {
            saveToSession(activity)
            activity.setOrigin(EntryOrigin.MIXED)
            activity.navigateTo(DrugEntryStep.CAPTURE)
        }

        // 保存 → 信息确认
        binding.saveButton.setOnClickListener {
            if (validate()) {
                saveToSession(activity)
                activity.navigateTo(DrugEntryStep.CONFIRM)
            }
        }
    }

    private fun setupDatePicker() {
        binding.expiryInput.isFocusable = false
        binding.expiryInput.isCursorVisible = false
        binding.expiryInput.keyListener = null

        binding.expiryInput.setOnClickListener { showDatePicker() }
        binding.expiryLayout.setOnClickListener { showDatePicker() }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        val currentText = binding.expiryInput.text?.toString() ?: ""
        if (currentText.matches(Regex("""\d{4}-\d{2}"""))) {
            val parts = currentText.split("-")
            calendar.set(Calendar.YEAR, parts[0].toInt())
            calendar.set(Calendar.MONTH, parts[1].toInt() - 1)
        }

        DatePickerDialog(
            requireContext(),
            { _, year, month, _ ->
                binding.expiryInput.setText(String.format("%04d-%02d", year, month + 1))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveToSession(activity: DrugEntryActivity) {
        val info = DrugInfo(
            drugName = binding.drugNameInput.text?.toString()?.trim() ?: "",
            expiryDate = binding.expiryInput.text?.toString()?.trim() ?: "",
            manufacturer = binding.manufacturerInput.text?.toString()?.trim() ?: "",
            batchNumber = binding.batchInput.text?.toString()?.trim() ?: ""
        )
        activity.updateDrugInfo(info, FieldStatus.MANUAL)
    }

    private fun validate(): Boolean {
        val drugName = binding.drugNameInput.text?.toString()?.trim() ?: ""

        binding.errorText.visibility = View.GONE

        if (drugName.isEmpty()) {
            binding.errorText.text = getString(R.string.error_drug_name_required)
            binding.errorText.visibility = View.VISIBLE
            binding.drugNameInput.requestFocus()
            return false
        }

        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
