package com.buxingjie.appfilemonitor.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.buxingjie.appfilemonitor.R
import com.buxingjie.appfilemonitor.core.DbHelper
import com.buxingjie.appfilemonitor.core.DownloadFinder
import com.buxingjie.appfilemonitor.core.Format
import com.buxingjie.appfilemonitor.core.MonitorWorker
import com.buxingjie.appfilemonitor.core.I18n
import com.buxingjie.appfilemonitor.core.Prefs
import com.buxingjie.appfilemonitor.core.Scanner
import com.buxingjie.appfilemonitor.core.Shell
import com.buxingjie.appfilemonitor.databinding.FragmentMonitorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: EventsAdapter
    private var target: String? = null
    private var filterType: String? = null
    private var scanning = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                toast(
                    if (grantResult == PackageManager.PERMISSION_GRANTED)
                        "Shizuku 权限已授予" else "Shizuku 权限被拒绝"
                )
                refreshAll()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    }

    override fun onStop() {
        super.onStop()
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = EventsAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.btnPick.setOnClickListener {
            AppPickerDialog().show(parentFragmentManager, "app_picker")
        }
        parentFragmentManager.setFragmentResultListener("pkg_picker", viewLifecycleOwner) { _, b ->
            val pkg = b.getString("pkg") ?: return@setFragmentResultListener
            Prefs.setTarget(requireContext(), pkg)
            target = pkg
            updateTargetInfo()
            refreshAll()
        }
        binding.chips.setOnCheckedStateChangeListener { _, ids ->
            filterType = when (ids.firstOrNull()) {
                R.id.chipAdded -> DbHelper.ADDED
                R.id.chipDeleted -> DbHelper.DELETED
                R.id.chipModified -> DbHelper.MODIFIED
                else -> null
            }
            loadEvents()
        }
        binding.btnScan.setOnClickListener { scanNow() }
        binding.btnMonitor.setOnClickListener { toggleMonitor() }
        binding.btnDownloads.setOnClickListener { findDownloads() }
        binding.btnGrant.setOnClickListener { requestShizuku() }
        binding.btnRoot.setOnClickListener { detectRoot() }
    }

    override fun onResume() {
        super.onResume()
        _binding?.root?.let { I18n.apply(it) }
        refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class Status(
        val running: Boolean,
        val granted: Boolean,
        val uid: Int,
        val mode: Shell.Mode,
        val privateOk: Boolean
    )

    private fun refreshAll() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                Shell.awaitBinder(1200)
                val mode = Shell.refresh(ctx)
                val pkg = Prefs.target(ctx)
                val ok = if (pkg == null) false else runCatching {
                    Shell.privateSupported(pkg)
                }.getOrDefault(false)
                Status(
                    Shell.shizukuRunning(),
                    Shell.shizukuGranted(),
                    Shell.shizukuUid,
                    mode,
                    ok
                )
            }
            target = Prefs.target(ctx)
            updateTargetInfo()
            renderStatus(status)
            updateMonitorButton()
            loadMeta()
            loadEvents()
        }
    }

    private fun updateTargetInfo() {
        val pkg = target
        if (pkg == null) {
            binding.tvAppName.text = "未选择"
            binding.tvAppPkg.text = "请选择要监控的应用"
            binding.btnScan.isEnabled = false
            binding.btnMonitor.isEnabled = false
            binding.btnDownloads.isEnabled = false
            return
        }
        runCatching {
            val pm = requireContext().packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            binding.tvAppName.text = pm.getApplicationLabel(ai)
            binding.tvAppPkg.text = pkg
        }.onFailure {
            binding.tvAppName.text = pkg
            binding.tvAppPkg.text = "应用未安装或不可用"
        }
        binding.btnScan.isEnabled = !scanning
        binding.btnMonitor.isEnabled = true
        binding.btnDownloads.isEnabled = true
    }

    private fun renderStatus(status: Status) {
        binding.tvMode.text = "执行模式：${status.mode.label}"
        val text = StringBuilder()
        text.append(if (status.running) "Shizuku：运行中" else "Shizuku：未运行/未安装")
        if (status.granted) {
            text.append("，已授权 (UID ${status.uid})")
        } else {
            text.append("，未授权")
        }
        if (target != null) {
            val fullRoot = status.mode == Shell.Mode.ROOT || status.mode == Shell.Mode.SHIZUKU_ROOT
            val download = if (Prefs.includeDownload(requireContext())) " + Download" else ""
            text.append("\n监控范围：外部数据 + 外部媒体").append(download)
            text.append(if (fullRoot) "（Root）" else "（免 Root）")
            text.append("\n私有数据：").append(
                when {
                    fullRoot -> "可访问（Root）"
                    status.privateOk -> "可访问（debuggable / run-as）"
                    else -> "不可访问（需 Root 或 debuggable）"
                }
            )
        }
        binding.tvPerm.text = text.toString()
        binding.btnGrant.visibility =
            if (status.running && !status.granted) View.VISIBLE else View.GONE
        binding.btnGrant.isEnabled = status.running && !status.granted
    }

    private fun updateMonitorButton() {
        val pkg = target
        if (pkg == null) {
            binding.btnMonitor.text = "开始监控"
            return
        }
        val watching = Prefs.monitored(requireContext()).contains(pkg)
        binding.btnMonitor.text = if (watching) "停止监控" else "开始监控"
    }

    private fun loadMeta() {
        val pkg = target ?: run {
            binding.tvSummary.text = "已记录 0 个文件"
            return
        }
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val metas = withContext(Dispatchers.IO) { DbHelper.get(ctx).rootMetas(pkg) }
            val total = metas.sumOf { it.count }
            val last = metas.maxOfOrNull { it.lastScan } ?: 0L
            if (_binding == null) return@launch
            binding.tvSummary.text = "已记录 $total 个文件 · 上次扫描 ${Format.time(last)}"
        }
    }

    private fun loadEvents() {
        val pkg = target ?: run {
            adapter.submit(emptyList())
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvLocations.text = "新增/下载位置：暂无记录"
            return
        }
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val db = DbHelper.get(ctx)
                Triple(
                    db.recentEvents(pkg, filterType),
                    db.recentEvents(pkg, DbHelper.ADDED),
                    db.allFilePaths(pkg)
                )
            }
            if (_binding == null) return@launch
            adapter.submit(data.first)
            binding.tvEmpty.visibility = if (data.first.isEmpty()) View.VISIBLE else View.GONE
            renderLocations(data.second, data.third)
        }
    }

    private fun renderLocations(added: List<DbHelper.Event>, paths: List<String>) {
        if (added.isNotEmpty()) {
            val groups = added
                .groupBy { it.path.substringBeforeLast('/', "/") }
                .entries
                .sortedByDescending { it.value.size }
                .take(4)
            val sb = StringBuilder("新增/下载位置")
            for (g in groups) {
                sb.append("\n").append(g.key).append("  (").append(g.value.size).append(" 个文件)")
            }
            binding.tvLocations.text = sb.toString()
            return
        }
        if (paths.isEmpty()) {
            binding.tvLocations.text = "新增/下载位置：暂无记录（首次扫描会建立基线）"
            return
        }
        val groups = paths
            .groupBy { it.substringBeforeLast('/', "/") }
            .entries
            .sortedByDescending { it.value.size }
            .take(4)
        val sb = StringBuilder("文件分布（当前）")
        for (g in groups) {
            sb.append("\n").append(g.key).append("  (").append(g.value.size).append(" 个文件)")
        }
        binding.tvLocations.text = sb.toString()
    }

    private fun scanNow() {
        if (scanning) return
        val pkg = target ?: return toast("请先选择应用")
        val ctx = requireContext().applicationContext
        scanning = true
        binding.btnScan.isEnabled = false
        binding.btnScan.text = "扫描中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Scanner(ctx).scan(pkg, Prefs.includeDownload(ctx))
                }.getOrNull()
            }
            scanning = false
            binding.btnScan.isEnabled = true
            binding.btnScan.text = "扫描一次"
            if (result == null) {
                toast("扫描失败，请检查权限状态")
                return@launch
            }
            val message = if (result.baselineRoots > 0 &&
                result.added == 0 && result.deleted == 0 && result.modified == 0
            ) {
                "已建立基线：${result.total} 个文件（耗时 ${result.durationMs}ms）"
            } else {
                "扫描完成：新增 ${result.added} · 删除 ${result.deleted} · 修改 ${result.modified}"
            }
            toast(
                if (result.privateSkipped) "$message\n私有目录不可访问，已跳过" else message
            )
            if (result.errors.isNotEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("部分目录不可访问")
                    .setMessage(result.errors.joinToString("\n"))
                    .setPositiveButton("知道了", null)
                    .show()
            }
            loadMeta()
            loadEvents()
        }
    }

    private fun toggleMonitor() {
        val pkg = target ?: return toast("请先选择应用")
        val ctx = requireContext()
        val set = Prefs.monitored(ctx).toMutableSet()
        val watching = set.contains(pkg)
        if (watching) {
            set.remove(pkg)
            toast("已停止监控")
        } else {
            set.add(pkg)
            toast("已开始监控，每 15 分钟自动扫描")
        }
        Prefs.setMonitored(ctx, set)
        if (set.isEmpty()) {
            MonitorWorker.cancel(ctx)
        } else {
            MonitorWorker.schedule(ctx)
        }
        updateMonitorButton()
    }

    private fun findDownloads() {
        val pkg = target ?: return toast("请先选择应用")
        binding.btnDownloads.isEnabled = false
        binding.btnDownloads.text = "查询中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { DownloadFinder.find(pkg) }.getOrNull()
            }
            if (_binding == null) return@launch
            binding.btnDownloads.isEnabled = true
            binding.btnDownloads.text = "查找该应用的下载文件（免 Root）"
            if (found == null) {
                toast("查询失败")
                return@launch
            }
            if (found.items.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("未找到共享存储中的下载文件")
                    .setMessage(
                        found.error
                            ?: "MediaStore / DownloadManager 中没有该应用的记录。\n\n" +
                            "很多应用会直接写入私有目录或 Android/data，这类位置无法通过免 Root 查询。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }
            val shown = found.items.take(50)
            val msg = buildString {
                for (item in shown) {
                    append(item.path).append("\n来源：").append(item.source).append("\n\n")
                }
                if (found.items.size > shown.size) {
                    append("仅显示前 ${shown.size} 条，共 ${found.items.size} 条。")
                }
            }
            AlertDialog.Builder(requireContext())
                .setTitle("该应用在共享存储中的文件（${found.items.size}）")
                .setMessage(msg.trim())
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun requestShizuku() {
        try {
            Shell.requestPermission(REQ_SHIZUKU)
        } catch (t: Throwable) {
            toast(t.message ?: "请求失败")
        }
    }

    private fun detectRoot() {
        val ctx = requireContext().applicationContext
        toast("正在检测 Root…")
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Shell.detectRoot(ctx) }
            toast(if (ok) "已切换到 Root 模式" else "未获得 Root（su 不可用）")
            refreshAll()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), I18n.t(requireContext(), msg), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
    }
}
