package com.buxingjie.appfilemonitor.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.buxingjie.appfilemonitor.databinding.ItemAppBinding

data class InstalledApp(val pkg: String, val label: String, val icon: Drawable)

class AppAdapter(private val onClick: (InstalledApp) -> Unit) :
    RecyclerView.Adapter<AppAdapter.VH>() {

    private var all: List<InstalledApp> = emptyList()
    private var shown: List<InstalledApp> = emptyList()
    private var query: String = ""

    fun submit(list: List<InstalledApp>) {
        all = list
        applyFilter()
    }

    fun filter(text: String) {
        query = text.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        shown = if (query.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.lowercase().contains(query) || it.pkg.lowercase().contains(query)
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = shown[position]
        holder.binding.tvLabel.text = app.label
        holder.binding.tvPkg.text = app.pkg
        holder.binding.imgIcon.setImageDrawable(app.icon)
        holder.binding.root.setOnClickListener { onClick(app) }
    }

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)
}
