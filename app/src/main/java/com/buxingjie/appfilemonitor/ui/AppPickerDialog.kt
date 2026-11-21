package com.buxingjie.appfilemonitor.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.buxingjie.appfilemonitor.databinding.DialogAppPickerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

class AppPickerDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAppPickerBinding.inflate(layoutInflater)
        val adapter = AppAdapter { app ->
            parentFragmentManager.setFragmentResult(
                "pkg_picker",
                Bundle().apply { putString("pkg", app.pkg) }
            )
            dismissAllowingStateLoss()
        }
        binding.recyclerApps.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerApps.adapter = adapter
        binding.inputSearch.doAfterTextChanged {
            adapter.filter(it?.toString().orEmpty())
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择应用")
            .setView(binding.root)
            .setNegativeButton("取消", null)
            .create()

        val ctx = requireContext().applicationContext
        thread(isDaemon = true) {
            val pm = ctx.packageManager
            val self = ctx.packageName
            val list = ArrayList<InstalledApp>()
            for (p in pm.getInstalledPackages(0)) {
                if (p.packageName == self) continue
                val ai = p.applicationInfo ?: continue
                val label = runCatching { ai.loadLabel(pm).toString() }
                    .getOrDefault(p.packageName)
                val icon = runCatching { ai.loadIcon(pm) }.getOrNull()
                    ?: pm.defaultActivityIcon
                list.add(InstalledApp(p.packageName, label, icon))
            }
            list.sortBy { it.label.lowercase() }
            activity?.runOnUiThread {
                binding.progress.visibility = View.GONE
                adapter.submit(list)
            }
        }

        return dialog
    }
}
