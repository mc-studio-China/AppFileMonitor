package com.buxingjie.appfilemonitor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.buxingjie.appfilemonitor.R
import com.buxingjie.appfilemonitor.core.Format
import com.buxingjie.appfilemonitor.core.Fs
import com.buxingjie.appfilemonitor.databinding.ItemFileBinding

class FilesAdapter(
    private val onClick: (Fs.Entry) -> Unit,
    private val onLongClick: (Fs.Entry) -> Unit
) : RecyclerView.Adapter<FilesAdapter.VH>() {

    private var items: List<Fs.Entry> = emptyList()

    fun submit(list: List<Fs.Entry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val b = holder.binding
        b.imgIcon.setImageResource(if (e.isDir) R.drawable.ic_folder else R.drawable.ic_file)
        b.tvName.text = e.name
        val meta = if (e.isDir) "目录" else Format.size(e.size)
        b.tvMeta.text = "$meta · ${Format.time(e.mtime * 1000)}"
        b.root.setOnClickListener { onClick(e) }
        b.root.setOnLongClickListener {
            onLongClick(e)
            true
        }
    }

    class VH(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)
}
