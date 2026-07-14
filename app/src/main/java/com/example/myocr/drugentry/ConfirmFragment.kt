package com.example.myocr.drugentry

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myocr.R
import java.util.Calendar

/**
 * 信息确认页
 *
 * 展示 OCR 识别结果，用户可编辑所有字段。
 * 有效期字段点击弹出日期选择器。
 * 提交后保存药品信息并跳转到保存完成页。
 */
class ConfirmFragment : Fragment() {

    private var _binding: com.example.myocr.databinding.FragmentConfirmBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = com.example.myocr.databinding.FragmentConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as DrugEntryActivity
        val session = activity.session

        // 返回
        binding.backButton.setOnClickListener { activity.supportFragmentManager.popBackStack() }

        // 填充字段
        binding.drugNameInput.setText(session.drugInfo.drugName)
        binding.expiryInput.setText(session.drugInfo.expiryDate)
        binding.manufacturerInput.setText(session.drugInfo.manufacturer)
        binding.batchInput.setText(session.drugInfo.batchNumber)

        // 显示已拍照片
        if (session.capturedPhotos.isNotEmpty()) {
            binding.photoRecycler.visibility = View.VISIBLE
            binding.photoRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            binding.photoRecycler.adapter = PhotoThumbnailAdapter(session.capturedPhotos)
        }

        // 有效期 → 点击弹出日期选择器
        setupDatePicker()

        // 实时同步编辑到 session（文字变更类字段）
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                syncToSession(activity)
            }
        }
        binding.drugNameInput.addTextChangedListener(textWatcher)
        binding.manufacturerInput.addTextChangedListener(textWatcher)
        binding.batchInput.addTextChangedListener(textWatcher)

        // 保存
        binding.saveButton.setOnClickListener {
            if (validate()) {
                syncToSession(activity)
                activity.navigateTo(DrugEntryStep.SAVED)
            }
        }
    }

    /**
     * 有效期字段设为只读，点击弹出日期选择器（支持年月/年月日）
     */
    private fun setupDatePicker() {
        // 设为不可直接编辑
        binding.expiryInput.isFocusable = false
        binding.expiryInput.isCursorVisible = false
        binding.expiryInput.keyListener = null

        binding.expiryInput.setOnClickListener { showDatePicker() }
        binding.expiryLayout.setOnClickListener { showDatePicker() }
        binding.expiryLayout.setStartIconOnClickListener { showDatePicker() }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()

        // 尝试从已有值解析年月日 / 年月
        val currentText = binding.expiryInput.text?.toString() ?: ""
        if (currentText.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            val parts = currentText.split("-")
            calendar.set(Calendar.YEAR, parts[0].toInt())
            calendar.set(Calendar.MONTH, parts[1].toInt() - 1)
            calendar.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
        } else if (currentText.matches(Regex("""\d{4}-\d{2}"""))) {
            val parts = currentText.split("-")
            calendar.set(Calendar.YEAR, parts[0].toInt())
            calendar.set(Calendar.MONTH, parts[1].toInt() - 1)
        }

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val formatted = String.format("%04d-%02d-%02d", year, month + 1, day)
                binding.expiryInput.setText(formatted)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()  // 显示完整日期选择器（年月日）
    }

    private fun syncToSession(activity: DrugEntryActivity) {
        val info = DrugInfo(
            drugName = binding.drugNameInput.text?.toString()?.trim() ?: "",
            expiryDate = binding.expiryInput.text?.toString()?.trim() ?: "",
            manufacturer = binding.manufacturerInput.text?.toString()?.trim() ?: "",
            batchNumber = binding.batchInput.text?.toString()?.trim() ?: ""
        )
        activity.updateDrugInfo(info, FieldStatus.CONFIRMED)
    }

    private fun validate(): Boolean {
        val drugName = binding.drugNameInput.text?.toString()?.trim() ?: ""
        val expiry = binding.expiryInput.text?.toString()?.trim() ?: ""

        binding.errorText.visibility = View.GONE

        if (drugName.isEmpty()) {
            binding.errorText.text = getString(R.string.error_drug_name_required)
            binding.errorText.visibility = View.VISIBLE
            binding.drugNameInput.requestFocus()
            return false
        }

        if (expiry.isNotEmpty() && !expiry.matches(Regex("""\d{4}-\d{2}(?:-\d{2})?"""))) {
            binding.errorText.text = getString(R.string.error_invalid_date)
            binding.errorText.visibility = View.VISIBLE
            binding.expiryInput.requestFocus()
            return false
        }

        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
