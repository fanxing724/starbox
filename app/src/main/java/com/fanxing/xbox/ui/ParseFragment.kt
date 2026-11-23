package com.fanxing.xbox.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fanxing.xbox.databinding.FragmentParseBinding
import com.fanxing.xbox.download.DownloadService
import com.fanxing.xbox.download.Engine
import com.fanxing.xbox.download.Task
import com.fanxing.xbox.drive.Drive
import com.fanxing.xbox.drive.Registry
import com.fanxing.xbox.drive.RemoteFile
import com.fanxing.xbox.drive.ShareRef
import com.fanxing.xbox.drive.expandMirrors
import com.fanxing.xbox.store.Creds
import com.fanxing.xbox.store.CredsHolder
import com.fanxing.xbox.store.History
import com.fanxing.xbox.store.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ParseFragment : Fragment() {

    private var _b: FragmentParseBinding? = null
    private val b get() = _b!!

    var pendingLink: String? = null

    private lateinit var adapter: FilesAdapter
    private var drive: Drive? = null
    private var share: ShareRef? = null
    private var files: List<RemoteFile> = emptyList()
    private var statusText = "粘贴链接后点解析"

    /** 目录栈：空=分享根目录 */
    private val dirStack = ArrayList<RemoteFile>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentParseBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        adapter = FilesAdapter { dir -> openDir(dir) }
        adapter.onSelectionChanged = { updateDownloadButton() }
        b.fileList.layoutManager = LinearLayoutManager(requireContext())
        b.fileList.adapter = adapter
        adapter.submit(files)
        b.status.text = statusText
        updateEmpty(); updateDownloadButton(); updateNav()

        b.btnParse.setOnClickListener { parse() }
        b.btnPaste.setOnClickListener { pasteFromClipboard() }
        b.btnHistory.setOnClickListener { showHistory() }
        b.btnDownload.setOnClickListener { downloadSelected() }
        b.btnTransfer.setOnClickListener { transferThenDownload() }
        b.btnUp.setOnClickListener { goUp() }

        // 系统返回键：在子目录里先回上层，而不是直接退出
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (dirStack.isNotEmpty()) goUp()
                    else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        askPermissions()
        consumePending()
    }

    fun consumePending() {
        val link = pendingLink ?: return
        if (_b == null) return
        b.inputLink.setText(link)
        pendingLink = null
        parse()
    }

    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (t.isNullOrBlank()) { toast("剪贴板是空的"); return }
        b.inputLink.setText(t.trim())
        parse()
    }

    private fun setStatus(s: String) {
        statusText = s
        _b?.status?.text = s
    }

    private fun updateEmpty() {
        _b?.empty?.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateNav() {
        _b?.navRow?.visibility = if (dirStack.isEmpty()) View.GONE else View.VISIBLE
        _b?.crumb?.text = "根目录 / " + dirStack.joinToString(" / ") { it.name }
    }

    private fun updateDownloadButton() {
        val n = adapter.selected.size
        _b?.btnDownload?.apply {
            isEnabled = n > 0
            text = if (n > 0) "下载选中（$n）" else "下载选中"
        }
        _b?.btnTransfer?.apply {
            visibility = if (drive?.supportsTransfer == true) View.VISIBLE else View.GONE
            isEnabled = n > 0
        }
    }

    private fun parse() {
        val url = b.inputLink.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) { toast("先粘贴一个分享链接"); return }
        val d = Registry.detect(url)
        if (d == null) { setStatus("无法识别该链接。支持：${Registry.names}"); return }
        drive = d
        dirStack.clear()
        setStatus("识别为 ${d.name}，正在解析…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val sh = withContext(Dispatchers.IO) { d.parse(url) }
                share = sh
                val list = withContext(Dispatchers.IO) { d.list(sh, null) }
                files = list
                adapter.submit(list)
                updateEmpty(); updateDownloadButton(); updateNav()
                History.get(requireContext()).add(url, d.name, list.firstOrNull()?.name ?: d.name)
                val needLogin = d.loginUrl != null && !Creds.get(requireContext()).has(d.id)
                setStatus("${d.name} · ${list.size} 项" + if (needLogin) " · 该盘需登录（去设置）" else "")
            } catch (e: Exception) {
                setStatus("解析失败：${e.message}")
            }
        }
    }

    private fun openDir(dir: RemoteFile) {
        dirStack.add(dir)
        loadDir(dir)
    }

    private fun goUp() {
        if (dirStack.isEmpty()) return
        dirStack.removeAt(dirStack.size - 1)
        loadDir(dirStack.lastOrNull())
    }

    private fun loadDir(dir: RemoteFile?) {
        val d = drive ?: return
        val sh = share ?: return
        setStatus(if (dir == null) "返回根目录…" else "进入 ${dir.name} …")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { d.list(sh, dir) }
                val prefix = dirStack.joinToString("/") { it.name }
                list.forEach { it.path = if (prefix.isBlank()) it.name else "$prefix/${it.name}" }
                files = list
                adapter.submit(list)
                updateEmpty(); updateDownloadButton(); updateNav()
                setStatus("${d.name} · ${dir?.name ?: "根目录"} · ${list.size} 项")
            } catch (e: Exception) {
                setStatus("打开目录失败：${e.message}")
            }
        }
    }

    private fun downloadSelected() {
        val d = drive ?: return
        val sh = share ?: return
        val picked = adapter.selectedFiles()
        if (picked.isEmpty()) { toast("先勾选要下的文件"); return }
        enqueue(d, sh, picked)
    }

    private fun enqueue(d: Drive, sh: ShareRef, picked: List<RemoteFile>) {
        setStatus("正在取直链…")
        viewLifecycleOwner.lifecycleScope.launch {
            var ok = 0
            for (f in picked) {
                try {
                    val link = withContext(Dispatchers.IO) { d.link(sh, f) }
                    val sources =
                        if (CredsHolder.useMirror) expandMirrors(link.url, link.mirrors)
                        else listOf(link.url)
                    // 落盘到系统公共 Download 目录
                    val target = withContext(Dispatchers.IO) {
                        Storage.create(requireContext(), f.name.ifBlank { "file_${f.id}" })
                    }
                    Engine.submit(
                        Task(
                            id = UUID.randomUUID().toString(),
                            name = target.displayName,
                            sources = sources,
                            headers = link.headers,
                            savePath = target.file?.absolutePath ?: target.displayPath,
                            uri = target.uri?.toString(),
                            displayPath = target.displayPath,
                            size = f.size
                        )
                    )
                    ok++
                } catch (e: Exception) {
                    setStatus("「${f.name}」失败：${e.message}")
                }
            }
            if (ok > 0) {
                if (Creds.get(requireContext()).keepAlive) DownloadService.start(requireContext())
                setStatus("已加入 $ok 个任务 · 保存到 ${Storage.publicDirLabel()}")
                (activity as? MainActivity)?.goTasks()
            }
        }
    }

    /** 一键转存下载：先存进自己网盘，再用登录态取直链 */
    private fun transferThenDownload() {
        val d = drive ?: return
        val sh = share ?: return
        val picked = adapter.selectedFiles()
        if (picked.isEmpty()) { toast("先勾选要下的文件"); return }
        if (!Creds.get(requireContext()).has(d.id)) {
            toast("转存需要先登录 ${d.name}")
            startActivity(Intent(requireContext(), LoginActivity::class.java).putExtra("drive", d.id))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("转存后下载")
            .setMessage(
                "将 ${picked.size} 个文件先保存到您 ${d.name} 的「starbox临时转存」目录，" +
                    "再从个人网盘取直链下载。\n\n会占用您的网盘空间，下载后可自行删除。"
            )
            .setPositiveButton("开始") { _, _ ->
                setStatus("正在转存…")
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) { d.transfer(sh, picked) }
                        setStatus("转存完成，开始下载")
                        enqueue(d, sh, saved)
                    } catch (e: Exception) {
                        setStatus("转存失败：${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistory() {
        val h = History.get(requireContext())
        val items = h.all()
        if (items.isEmpty()) { toast("暂无历史解析"); return }
        AlertDialog.Builder(requireContext())
            .setTitle("历史解析（${items.size}）")
            .setItems(items.map { "${it.drive} · ${it.title}" }.toTypedArray()) { _, i ->
                b.inputLink.setText(items[i].url); parse()
            }
            .setNeutralButton("清空") { _, _ -> h.clear(); toast("已清空") }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun askPermissions() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) want.add(Manifest.permission.POST_NOTIFICATIONS)
        // Android 9 及以下写公共 Download 需要存储权限；10+ 走 MediaStore 不需要
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) want.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 100)
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
