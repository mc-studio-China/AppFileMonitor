package com.buxingjie.appfilemonitor.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.buxingjie.appfilemonitor.BuildConfig
import com.buxingjie.appfilemonitor.core.Prefs
import com.buxingjie.appfilemonitor.core.Shell
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
        binding.switchDownload.isChecked = Prefs.includeDownload(ctx)
        binding.switchDownload.setOnCheckedChangeListener { _, checked ->
            Prefs.setIncludeDownload(ctx, checked)
        }
        binding.btnGrantShizuku.setOnClickListener { requestShizuku() }
        binding.btnOpenShizuku.setOnClickListener { openShizuku() }
        binding.btnDetectRoot.setOnClickListener { detectRoot() }
        binding.btnRefreshStatus.setOnClickListener { refreshStatus() }
        binding.btnDisableRoot.setOnClickListener {
            Shell.disableRoot(requireContext())
            toast("已关闭 Root，仅使用 Shizuku")
            refreshStatus()
        }
        binding.btnCheckUpdate.setOnClickListener {
            UpdatePrompter(requireActivity() as androidx.appcompat.app.AppCompatActivity)
                .check(auto = false)
        }
        refreshStatus()
        loadSignature()
    }

    override fun onResume() {
        super.onResume()
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

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_SHIZUKU = 2002
    }
}
