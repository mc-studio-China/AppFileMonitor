package com.buxingjie.appfilemonitor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.buxingjie.appfilemonitor.R
import com.buxingjie.appfilemonitor.core.DbHelper
import com.buxingjie.appfilemonitor.core.Format
import com.buxingjie.appfilemonitor.databinding.ItemEventBinding

class EventsAdapter : RecyclerView.Adapter<EventsAdapter.VH>() {

    private var items: List<DbHelper.Event> = emptyList()

    fun submit(list: List<DbHelper.Event>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val b = holder.binding
        val label: String
        val colorRes: Int
        when (e.type) {
            DbHelper.ADDED -> {
                label = "新增"
                colorRes = R.color.added
            }

            DbHelper.DELETED -> {
                label = "删除"
                colorRes = R.color.deleted
            }

            else -> {
                label = "修改"
                colorRes = R.color.modified
            }
        }
        b.tvType.text = label
        b.tvType.setTextColor(ContextCompat.getColor(b.root.context, colorRes))
        b.tvPath.text = e.path
        val size = if (e.isDir) "目录" else Format.size(e.size)
        b.tvMeta.text = "${DbHelper.rootLabel(e.root)} · $size · 检测于 ${Format.time(e.detectedAt)}"
    }

    class VH(val binding: ItemEventBinding) : RecyclerView.ViewHolder(binding.root)
}
