package com.buxingjie.appfilemonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.buxingjie.appfilemonitor.R
import com.buxingjie.appfilemonitor.core.Format
import com.buxingjie.appfilemonitor.core.Fs
import com.buxingjie.appfilemonitor.core.I18n
import com.buxingjie.appfilemonitor.core.Prefs
import com.buxingjie.appfilemonitor.core.Shell
import com.buxingjie.appfilemonitor.databinding.FragmentFilesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private var adapter: FilesAdapter? = null
    private var target: String? = null
    private var rootId = "ext_data"
    private var currentPath: String? = null
    private var loading = false
    private var updatingChips = false
    private var privateOk = false
    private var privateChecked: String? = null
    private var allFilesPrompted = false

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startImport(uri)
        }

    private val folderImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) startFolderImport(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val a = FilesAdapter(
            onClick = { e ->
                if (e.isDir) loadDir(e.path) else showInfo(e)
            },
            onLongClick = { e -> showActions(e) }
        )
        adapter = a
        binding.fileList.layoutManager = LinearLayoutManager(requireContext())
        binding.fileList.adapter = a

        binding.btnPick.setOnClickListener {
            AppPickerDialog().show(parentFragmentManager, "app_picker")
        }
        parentFragmentManager.setFragmentResultListener("pkg_picker", viewLifecycleOwner) { _, b ->
            val pkg = b.getString("pkg") ?: return@setFragmentResultListener
            Prefs.setTarget(requireContext(), pkg)
            target = null
            rootId = "ext_data"
            currentPath = null
            privateOk = false
            privateChecked = null
            switchChip("ext_data")
            loadState()
        }

        binding.rootChips.setOnCheckedStateChangeListener { _, ids ->
            if (updatingChips) return@setOnCheckedStateChangeListener
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newRoot = when (id) {
                R.id.chipExtData -> "ext_data"
                R.id.chipExtMedia -> "ext_media"
                else -> "private"
            }
            if (newRoot == "private" && target != null && !Shell.isRoot() && !privateOk) {
                toast("私有目录不可访问：需要 Root，或目标应用为 debuggable")
                switchChip(rootId)
                return@setOnCheckedStateChangeListener
            }
            rootId = newRoot
            currentPath = null
            loadState()
        }

        binding.btnUp.setOnClickListener {
            val pkg = target ?: return@setOnClickListener
            val path = currentPath ?: return@setOnClickListener
            val base = rootPath(pkg, rootId)
            if (path != base && path.startsWith(base)) {
                val parent = path.substringBeforeLast('/').ifEmpty { "/" }
                if (parent.length >= base.length) loadDir(parent)
            }
        }
        binding.btnRefresh.setOnClickListener {
            currentPath?.let { loadDir(it) } ?: loadState()
        }
        binding.btnImport.setOnClickListener {
            if (target == null) {
                toast("请先选择应用")
            } else {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("导入")
                    .setItems(arrayOf("导入文件", "导入文件夹")) { _, which ->
                        if (which == 0) importLauncher.launch(arrayOf("*/*"))
                        else folderImportLauncher.launch(null)
                    }
                    .show()
            }
        }
        binding.btnMkdir.setOnClickListener { promptMkdir() }
    }

    override fun onResume() {
        super.onResume()
        _binding?.root?.let { I18n.apply(it) }
        loadState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
        _binding = null
    }

    private fun rootPath(pkg: String, id: String): String = when (id) {
        "ext_data" -> "/storage/emulated/0/Android/data/$pkg"
        "ext_media" -> "/storage/emulated/0/Android/media/$pkg"
        else -> "/data/data/$pkg"
    }

    private fun switchChip(id: String) {
        updatingChips = true
        binding.rootChips.check(
            when (id) {
                "ext_data" -> R.id.chipExtData
                "ext_media" -> R.id.chipExtMedia
                else -> R.id.chipPrivate
            }
        )
        updatingChips = false
    }

    private fun updateHeader() {
        val pkg = target
        if (pkg == null) {
            binding.tvApp.text = "未选择应用"
            binding.tvAppPkgFiles.text = "选择应用后可浏览其数据目录"
            return
        }
        runCatching {
            val pm = requireContext().packageManager
            binding.tvApp.text = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
            binding.tvAppPkgFiles.text = pkg
        }.onFailure {
            binding.tvApp.text = pkg
            binding.tvAppPkgFiles.text = "应用未安装或不可用"
        }
    }

    private fun loadState() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                Shell.awaitBinder(1000)
                Shell.refresh(ctx)
            }
            if (_binding == null) return@launch
            val pkg = Prefs.target(ctx)
            if (pkg != target) {
                target = pkg
                currentPath = null
                rootId = "ext_data"
                privateOk = false
                privateChecked = null
            }
            if (pkg != null && privateChecked != pkg) {
                privateOk = withContext(Dispatchers.IO) {
                    runCatching { Shell.privateSupported(pkg) }.getOrDefault(false)
                }
                privateChecked = pkg
            }
            if (rootId == "private" && !Shell.isRoot() && !privateOk) {
                rootId = "ext_data"
            }
            switchChip(rootId)
            updateHeader()
            updateCapability()
            if (pkg == null) {
                showEmpty("请选择应用")
                return@launch
            }
            val base = rootPath(pkg, rootId)
            val path = currentPath?.takeIf { it == base || it.startsWith("$base/") } ?: base
            loadDir(path)
        }
    }

    private fun updateCapability() {
        val pkg = target
        if (pkg == null) {
            binding.tvCapability.text = "免 Root：可读写外部数据/媒体"
            binding.btnImport.isEnabled = false
            binding.btnMkdir.isEnabled = false
            return
        }
        val fullRoot = Shell.isRoot()
        binding.tvCapability.text = when {
            fullRoot -> "Root 模式：私有数据 + 外部数据 + 外部媒体均可读写"
            privateOk -> "Shizuku 免 Root：外部数据/媒体可读写；该应用 debuggable，私有目录也可访问"
            else -> "Shizuku 免 Root：外部数据/媒体可读写；私有目录需 Root 或 debuggable"
        }
        val canWrite = fullRoot || privateOk || rootId != "private"
        binding.btnImport.isEnabled = canWrite
        binding.btnMkdir.isEnabled = canWrite
    }

    private fun loadDir(path: String) {
        val pkg = target ?: return
        if (loading) return
        loading = true
        val base = rootPath(pkg, rootId)
        binding.tvPath.text = path
        binding.btnUp.isEnabled = path != base
        viewLifecycleOwner.lifecycleScope.launch {
            val (list, err) = withContext(Dispatchers.IO) { Fs.list(pkg, path) }
            loading = false
            if (_binding == null) return@launch
            currentPath = path
            binding.tvPath.text = path
            binding.btnUp.isEnabled = path != base
            adapter?.submit(list)
            when {
                err != null -> showEmpty(
                    if (rootId == "private" && !privateOk) {
                        "私有目录不可访问\n\n" +
                            "Shizuku(Shell) 无法读取任意应用的 /data/data；" +
                            "需要 Root，或目标应用为 debuggable。\n请切换到「外部数据」。"
                    } else {
                        err
                    }
                )

                list.isEmpty() -> showEmpty(
                    "目录为空或不存在\n\n" +
                        "免 Root 模式可读写外部数据与外部媒体；" +
                        "私有目录需要 Root 或 debuggable 应用。"
                )

                else -> binding.tvFilesEmpty.visibility = View.GONE
            }
        }
    }

    private fun showEmpty(text: String) {
        adapter?.submit(emptyList())
        binding.tvFilesEmpty.text = text
        binding.tvFilesEmpty.visibility = View.VISIBLE
    }

    private fun startImport(uri: Uri) {
        runWithPermission { doImport(uri) }
    }

    private fun doImport(uri: Uri) {
        val pkg = target ?: return
        val dir = currentPath ?: return
        val name = queryName(uri) ?: "import_${System.currentTimeMillis()}.bin"
        promptText("导入到（可修改完整路径）", "$dir/$name") { dest ->
            if (!dest.startsWith("/")) {
                toast("请输入绝对路径")
                return@promptText
            }
            val ctx = requireContext().applicationContext
            toast("正在导入…")
            viewLifecycleOwner.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    val input = runCatching {
                        ctx.contentResolver.openInputStream(uri)
                    }.getOrNull() ?: return@withContext Shell.Result(-1, "", "无法读取所选文件")
                    Fs.importStream(pkg, dest, input)
                }
                toast(if (r.ok) "导入成功：$dest" else "导入失败：${r.message().take(200)}")
                if (r.ok) loadDir(dir)
            }
        }
    }


    private fun startFolderImport(uri: Uri) {
        runWithPermission { doFolderImport(uri) }
    }

    private fun doFolderImport(uri: Uri) {
        val pkg = target ?: return
        val dir = currentPath ?: return
        val ctx = requireContext().applicationContext
        val tree = runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull()
        if (tree == null) {
            toast("无法读取所选文件夹")
            return
        }
        val baseName = tree.name?.takeIf { it.isNotBlank() } ?: "imported_folder"
        // 同名文件夹自动加序号，避免和有内容的老文件夹混在一起
        var name = baseName
        var index = 2
        while (Fs.existsPath("$dir/$name")) {
            name = "$baseName($index)"
            index++
        }
        val dest = "$dir/$name"
        toast("正在导入文件夹…")
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { copyTree(ctx, pkg, tree, dest) }
            toast(r.first)
            if (r.second) loadDir(dir)
        }
    }

    private fun copyTree(
        ctx: Context,
        pkg: String,
        tree: DocumentFile,
        dest: String
    ): Pair<String, Boolean> {
        var ok = 0
        var fail = 0
        val root = Fs.mkdir(pkg, dest)
        if (!root.ok) return "导入失败：${root.message().take(160)}" to false

        fun walk(node: DocumentFile, target: String) {
            val children = runCatching { node.listFiles() }.getOrElse { emptyArray<DocumentFile>() }
            for (child in children) {
                val childName = child.name ?: continue
                val childPath = "$target/$childName"
                if (child.isDirectory) {
                    val r = Fs.mkdir(pkg, childPath)
                    if (r.ok) walk(child, childPath) else fail++
                } else {
                    val ins = runCatching {
                        ctx.contentResolver.openInputStream(child.uri)
                    }.getOrNull()
                    if (ins == null) {
                        fail++
                        continue
                    }
                    val r = Fs.importStream(pkg, childPath, ins)
                    if (r.ok) ok++ else fail++
                }
            }
        }
        walk(tree, dest)
        return "文件夹导入完成：成功 $ok 个文件，失败 $fail 个" to (fail == 0)
    }

    private fun queryName(uri: Uri): String? = runCatching {
        requireContext().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun promptMkdir() {
        val dir = currentPath ?: return toast("请先选择应用")
        promptText("新建文件夹（完整路径）", "$dir/new_folder") { dest ->
            if (!dest.startsWith("/")) {
                toast("请输入绝对路径")
                return@promptText
            }
            doOp("新建") { Fs.mkdir(target!!, dest) }
        }
    }

    private fun showInfo(e: Fs.Entry) {
        val msg = "路径：${e.path}\n" +
            "大小：${if (e.isDir) "目录" else Format.size(e.size)}\n" +
            "修改时间：${Format.time(e.mtime * 1000)}"
        AlertDialog.Builder(requireContext())
            .setTitle(e.name)
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showActions(e: Fs.Entry) {
        val items = arrayOf("复制到…", "移动到…", "重命名", "删除")
        AlertDialog.Builder(requireContext())
            .setTitle(e.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptText("复制到（完整路径）", "${e.path}.copy") { dest ->
                        doOp("复制") { Fs.copy(target!!, e.path, dest) }
                    }

                    1 -> promptText("移动到（完整路径）", e.path) { dest ->
                        doOp("移动") { Fs.move(target!!, e.path, dest) }
                    }

                    2 -> promptText("重命名（完整路径）", e.path) { dest ->
                        doOp("重命名") { Fs.rename(target!!, e.path, dest) }
                    }

                    3 -> AlertDialog.Builder(requireContext())
                        .setTitle("删除")
                        .setMessage("确定删除？\n${e.path}")
                        .setPositiveButton("删除") { _, _ ->
                            doOp("删除") { Fs.delete(target!!, e.path) }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    private fun doOp(label: String, block: () -> Shell.Result) {
        runWithPermission { doOpInner(label, block) }
    }

    private fun doOpInner(label: String, block: () -> Shell.Result) {
        val dir = currentPath
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            toast(if (r.ok) "${label}成功" else "${label}失败：${r.message().take(200)}")
            if (dir != null) loadDir(dir)
            if (!r.ok && !Fs.allFilesAccess()) {
                allFilesPrompted = true
                askAllFiles {}
            }
        }
    }

    /** 写操作前检查“所有文件访问”权限，未授权先提示一次，可选择继续尝试 */
    private fun runWithPermission(action: () -> Unit) {
        if (Fs.allFilesAccess() || allFilesPrompted) {
            action()
            return
        }
        allFilesPrompted = true
        askAllFiles(action)
    }

    private fun askAllFiles(continueAction: () -> Unit) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("需要「所有文件访问」权限")
            .setMessage(
                "Android 11 起，删除、新建、导入到别的应用的 Android/data 目录需要该权限。\n" +
                    "请到系统设置里给 FileDetection 打开「所有文件访问」，再回来重试。"
            )
            .setPositiveButton("去开启") { _, _ ->
                val ok = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:" + ctx.packageName))
                    )
                }.isSuccess
                if (!ok) {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:" + ctx.packageName))
                        )
                    }
                }
            }
            .setNegativeButton("继续尝试") { _, _ -> continueAction() }
            .show()
    }

    private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(requireContext()).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine(true)
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), I18n.t(requireContext(), msg), Toast.LENGTH_SHORT).show()
    }
}
