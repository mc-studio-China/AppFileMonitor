package com.buxingjie.appfilemonitor.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.buxingjie.appfilemonitor.BuildConfig
import com.buxingjie.appfilemonitor.core.Fs
import com.buxingjie.appfilemonitor.core.I18n
import com.buxingjie.appfilemonitor.core.Prefs
import com.buxingjie.appfilemonitor.core.Shell
import com.buxingjie.appfilemonitor.core.UpdateRoutes
import com.buxingjie.appfilemonitor.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.security.MessageDigest

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                toast(
                    if (grantResult == PackageManager.PERMISSION_GRANTED)
                        "Shizuku 权限已授予" else "Shizuku 权限被拒绝"
                )
                refreshStatus()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
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
        val ctx = requireContext()
        binding.tvVersion.text = "应用文件监控 v${BuildConfig.VERSION_NAME}"
        binding.tvUpdateStatus.text =
            "当前版本 v${BuildConfig.VERSION_NAME}\n" +
                "启动时自动检测 GitHub 最新版本；应用内下载会自动使用镜像站加速。"
        binding.switchDownload.isChecked = Prefs.includeDownload(ctx)
        binding.switchDownload.setOnCheckedChangeListener { _, checked ->
            Prefs.setIncludeDownload(ctx, checked)
        }
        binding.btnGrantShizuku.setOnClickListener { requestShizuku() }
        binding.btnOpenShizuku.setOnClickListener { openShizuku() }
        binding.btnDetectRoot.setOnClickListener { detectRoot() }
        binding.btnAllFiles.text =
            if (Fs.allFilesAccess()) "所有文件访问：已开启" else "所有文件访问：未开启（点此开启）"
        binding.btnAllFiles.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure { toast("无法打开权限页") }
        }

        binding.btnRefreshStatus.setOnClickListener { refreshStatus() }
        binding.btnDisableRoot.setOnClickListener {
            Shell.disableRoot(requireContext())
            toast("已关闭 Root，仅使用 Shizuku")
            refreshStatus()
        }
        binding.tvLanguage.text = languageLabel(ctx)
        binding.btnLanguage.setOnClickListener { showLanguageDialog() }
        binding.btnMethodWifi.setOnClickListener { showGuide("无线调试（免电脑）",
            "1. 下拉通知栏，点「打开 Shizuku」\n" +
            "2. 在 Shizuku 里选择「通过无线调试启动」→ 点「配对」\n" +
            "3. 打开系统「开发者选项 → 无线调试 → 使用配对码配对设备」，输入 6 位配对码\n" +
            "4. 配对成功后回到 Shizuku 点「启动」，状态变为「运行中」\n\n" +
            "提示：Android 11 及以上才支持无线调试；部分机型需保持屏幕常亮。", null) }
        binding.btnMethodRoot.setOnClickListener { showGuide("Root 启动",
            "1. 确认手机已 Root，并已授予 Shizuku 管理员权限\n" +
            "2. 打开 Shizuku → 选择「通过 Root 启动」\n" +
            "3. 返回本应用，点「授权 Shizuku」，再点「检测 Root」\n\n" +
            "Root 模式下可读写任意应用的私有目录。", null) }
        binding.btnMethodAdb.setOnClickListener { showGuide("USB / ADB 启动",
            "1. 手机开启「USB 调试」，用数据线连接电脑\n" +
            "2. 电脑命令行执行下面这条命令启动 Shizuku：",
            "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh") }
        binding.btnUpdateRoute.setOnClickListener {
            pickRoute()
        }

        binding.btnCheckUpdate.setOnClickListener {
            UpdatePrompter(requireActivity() as androidx.appcompat.app.AppCompatActivity)
                .check(auto = false)
        }
        refreshRoute()
        refreshStatus()
        loadSignature()
    }

    override fun onResume() {
        super.onResume()
        _binding?.root?.let { I18n.apply(it) }
        refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class Status(
        val installed: Boolean,
        val running: Boolean,
        val granted: Boolean,
        val uid: Int,
        val mode: Shell.Mode,
        val rootOk: Boolean
    )

    private fun refreshStatus() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) {
                Shell.awaitBinder(1500)
                val installed = runCatching {
                    ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                }.isSuccess
                val mode = Shell.refresh(ctx)
                Status(
                    installed,
                    Shell.shizukuRunning(),
                    Shell.shizukuGranted(),
                    Shell.shizukuUid,
                    mode,
                    Prefs.rootVerified(ctx)
                )
            }
            if (_binding == null) return@launch
            binding.tvShizuku.text = when {
                !st.installed -> "Shizuku：未安装"
                !st.running -> "Shizuku：已安装，服务未运行"
                st.granted -> "Shizuku：已授权 (UID ${st.uid})"
                else -> "Shizuku：运行中，未授权"
            }
            binding.tvMode.text = "执行模式：${st.mode.label}"
            binding.tvRoot.text = if (st.rootOk) "Root：已启用（su）" else "Root：未启用"
            binding.tvCapabilities.text = when (st.mode) {
                Shell.Mode.ROOT, Shell.Mode.SHIZUKU_ROOT ->
                    "权限范围：私有目录 + 外部数据/媒体 + Download"
                Shell.Mode.SHIZUKU_SHELL ->
                    "免 Root 模式：外部数据/媒体 + Download\n私有目录：仅 debuggable 应用可访问"
                Shell.Mode.NONE ->
                    "未授权：仅能访问共享存储（受系统限制）"
            }
            binding.btnGrantShizuku.isEnabled = st.running && !st.granted
            binding.btnDisableRoot.isEnabled = Shell.isRoot()
        }
    }

    private fun requestShizuku() {
        try {
            Shell.requestPermission(REQ_SHIZUKU)
        } catch (t: Throwable) {
            toast(t.message ?: "请求失败")
        }
    }

    private fun openShizuku() {
        val intent = requireContext().packageManager
            .getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent == null) {
            toast("未安装 Shizuku")
        } else {
            runCatching { startActivity(intent) }
        }
    }

    private fun detectRoot() {
        val ctx = requireContext().applicationContext
        toast("正在检测 Root…")
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Shell.detectRoot(ctx) }
            toast(if (ok) "已获得 Root 权限" else "未获得 Root（su 不可用）")
            refreshStatus()
        }
    }

    private fun loadSignature() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val sha = withContext(Dispatchers.IO) {
                runCatching { signSha256(ctx) }.getOrNull()
            }
            if (_binding == null) return@launch
            binding.tvSignature.text = if (sha == null) "读取失败" else "SHA-256\n$sha"
        }
    }

    private fun signSha256(ctx: Context): String? {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString(":") { String.format("%02X", it) }
    }

    private fun languageLabel(ctx: Context): String {
        val code = Prefs.lang(ctx)
        return if (code == I18n.AUTO) I18n.labelOf(I18n.AUTO) else I18n.labelOf(code)
    }

    private fun showLanguageDialog() {
        val ctx = requireContext()
        val codes = ArrayList<String>().apply {
            add(I18n.AUTO)
            I18n.LANGS.forEach { add(it.code) }
        }
        val names = codes.map {
            if (it == I18n.AUTO) I18n.labelOf(I18n.AUTO) else I18n.labelOf(it)
        }.toTypedArray()
        val current = codes.indexOf(Prefs.lang(ctx)).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("选择界面语言")
            .setSingleChoiceItems(names, current) { dialog, which ->
                Prefs.setLang(ctx, codes[which])
                dialog.dismiss()
                requireActivity().recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGuide(title: String, body: String, copyText: String?) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(body)
            .setNegativeButton("关闭", null)
        if (copyText != null) {
            builder.setPositiveButton("复制命令") { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", copyText))
                toast("已复制到剪贴板")
            }
        }
        builder.show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), I18n.t(requireContext(), msg), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_SHIZUKU = 2002
    }

    private fun refreshRoute() {
        val ctx = requireContext()
        binding.btnUpdateRoute.text = "更新线路：" + UpdateRoutes.label(Prefs.updateRoute(ctx))
    }

    private fun pickRoute() {
        val ctx = requireContext()
        val routes = UpdateRoutes.ROUTES
        val current = routes.indexOfFirst { it.id == Prefs.updateRoute(ctx) }.coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle("选择更新线路")
            .setSingleChoiceItems(routes.map { it.label }.toTypedArray(), current) { d, which ->
                Prefs.setUpdateRoute(ctx, routes[which].id)
                refreshRoute()
                d.dismiss()
                toast("已切换为：" + routes[which].label)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
