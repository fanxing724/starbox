package com.fanxing.xbox.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fanxing.xbox.databinding.ItemTaskBinding
import com.fanxing.xbox.download.Engine
import com.fanxing.xbox.download.State
import com.fanxing.xbox.download.Task

class TasksAdapter : RecyclerView.Adapter<TasksAdapter.VH>() {

    class VH(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = Engine.tasks.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val t: Task = Engine.tasks[position]
        h.b.name.text = t.name
        h.b.progress.setProgressCompat(t.percent, false)

        val state = when (t.state) {
            State.QUEUED -> "排队中"
            State.RUNNING -> "下载中"
            State.PAUSED -> "已暂停"
            State.DONE -> "已完成 · 点击打开"
            State.ERROR -> "出错"
        }
        h.b.info.text = buildString {
            append(state)
            append(" · ${t.percent}% · ${Fmt.size(t.downloaded)}/${Fmt.size(t.size)}")
            if (t.state == State.RUNNING) append(" · ${Fmt.speed(t.speed)}")
            if (t.error.isNotBlank()) append("\n${t.error}")
        }

        h.b.btnToggle.text =
            if (t.state == State.RUNNING || t.state == State.QUEUED) "暂停" else "继续"
        h.b.btnToggle.isEnabled = t.state != State.DONE
        h.b.btnToggle.setOnClickListener {
            if (t.state == State.RUNNING || t.state == State.QUEUED) Engine.pause(t.id)
            else Engine.resume(t.id)
        }
        h.b.btnDelete.setOnClickListener { Engine.remove(t.id); notifyDataSetChanged() }
        h.b.root.setOnClickListener {
            if (t.state == State.DONE) OpenFile.open(h.b.root.context, t.savePath, t.uri)
        }
    }
}
