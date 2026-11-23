package com.fanxing.xbox.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fanxing.xbox.R
import com.fanxing.xbox.databinding.ItemFileBinding
import com.fanxing.xbox.drive.RemoteFile

class FilesAdapter(
    private val onOpenDir: (RemoteFile) -> Unit
) : RecyclerView.Adapter<FilesAdapter.VH>() {

    val items = ArrayList<RemoteFile>()
    val selected = HashSet<String>()
    var onSelectionChanged: (() -> Unit)? = null

    fun submit(list: List<RemoteFile>) {
        items.clear(); items.addAll(list); selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    class VH(val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        h.b.name.text = f.name
        h.b.meta.text = if (f.isDir) "文件夹 · 点击进入" else Fmt.size(f.size)
        h.b.icon.setImageResource(if (f.isDir) R.drawable.ic_folder else R.drawable.ic_file)
        h.b.check.visibility = if (f.isDir) View.GONE else View.VISIBLE
        h.b.check.setOnCheckedChangeListener(null)
        h.b.check.isChecked = selected.contains(f.id)
        h.b.check.setOnCheckedChangeListener { _, on ->
            if (on) selected.add(f.id) else selected.remove(f.id)
            onSelectionChanged?.invoke()
        }
        h.b.root.setOnClickListener {
            if (f.isDir) onOpenDir(f)
            else {
                if (selected.contains(f.id)) selected.remove(f.id) else selected.add(f.id)
                notifyItemChanged(position)
                onSelectionChanged?.invoke()
            }
        }
    }

    fun selectedFiles(): List<RemoteFile> = items.filter { !it.isDir && selected.contains(it.id) }
}
