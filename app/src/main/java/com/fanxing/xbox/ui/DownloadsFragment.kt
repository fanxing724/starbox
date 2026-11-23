package com.fanxing.xbox.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.fanxing.xbox.databinding.FragmentDownloadsBinding
import com.fanxing.xbox.download.Engine

class DownloadsFragment : Fragment() {

    private var _b: FragmentDownloadsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: TasksAdapter

    private val listener: () -> Unit = {
        _b?.root?.post {
            if (_b != null) {
                adapter.notifyDataSetChanged()
                b.empty.visibility = if (Engine.tasks.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDownloadsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        adapter = TasksAdapter()
        b.taskList.layoutManager = LinearLayoutManager(requireContext())
        b.taskList.adapter = adapter
        b.savePath.text = "保存位置：" + com.fanxing.xbox.store.Storage.publicDirLabel()
        b.empty.visibility = if (Engine.tasks.isEmpty()) View.VISIBLE else View.GONE
        Engine.addListener(listener)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        Engine.removeListener(listener)
        _b = null
        super.onDestroyView()
    }
}
