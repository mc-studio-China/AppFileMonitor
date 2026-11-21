package com.buxingjie.appfilemonitor.ui

import android.content.ClipData
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.buxingjie.appfilemonitor.BuildConfig
import com.buxingjie.appfilemonitor.core.Prefs
import com.buxingjie.appfilemonitor.core.UpdateChecker
import com.buxingjie.appfilemonitor.core.UpdateDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 更新提示与下载安装。进入应用时自动检查，也可从设置页手动触发。
 */
class UpdatePrompter(private val activity: AppCompatActivity) {

    fun check(auto: Boolean) {
        if (checking) return
        checking = true
        if (!auto) toast("正在检查更新…")
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    UpdateChecker.fetch()
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                result.onSuccess { info ->
                    if (!UpdateChecker.isNewer(BuildConfig.VERSION_NAME, info.version)) {
                        if (!auto) toast("已是最新版本 v${BuildConfig.VERSION_NAME}")
                        return@onSuccess
                    }
                    if (auto && Prefs.ignoredVersion(activity) == info.version) return@onSuccess
                    showDialog(info)
                }.onFailure { error ->
                    if (!auto) {
                        AlertDialog.Builder(activity)
                            .setTitle("检查更新失败")
                            .setMessage(error.message ?: "无法连接 GitHub")
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }
            } finally {
                checking = false
            }
        }
    }

    private fun showDialog(info: UpdateChecker.Info) {
        val size = if (info.apkSize > 0) {
            "%.1f MB".format(info.apkSize / 1024.0 / 1024.0)
        } else {
            "未知"
        }
        val changelog = info.changelog.ifBlank { "（无更新说明）" }.let {
            if (it.length > 3000) it.take(3000) + "\n…" else it
        }
        val message = buildString {
            append("当前版本：v").append(BuildConfig.VERSION_NAME)
            append("\n最新版本：v").append(info.version)
            append("\n\n更新内容：\n").append(changelog)
            append("\n\n安装包大小：").append(size)
            append("\n应用内下载会自动使用镜像站加速。")
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setMessage(message)
            .setPositiveButton("应用内下载", null)
            .setNeutralButton("网页下载") { _, _ -> openPage(info.pageUrl) }
            .setNegativeButton("忽略") { _, _ -> Prefs.setIgnoredVersion(activity, info.version) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                download(info)
            }
        }
        dialog.show()
    }

    private fun download(info: UpdateChecker.Info) {
        if (info.apkUrl == null) {
            toast("该版本没有 APK 附件，请使用网页下载")
            openPage(info.pageUrl)
            return
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val tv = TextView(activity).apply { text = "准备下载…" }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        container.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        var cancelled = false
        val progress = AlertDialog.Builder(activity)
            .setTitle("正在下载 v${info.version}")
            .setView(container)
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        progress.setOnShowListener {
            progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelled = true
                progress.dismiss()
                toast("已取消下载")
            }
        }
        progress.show()

        activity.lifecycleScope.launch {
            val result: Result<File> = withContext(Dispatchers.IO) {
                try {
                    UpdateDownloader.download(
                        activity.applicationContext,
                        info,
                        { pct ->
                            activity.runOnUiThread {
                                if (cancelled || activity.isFinishing || activity.isDestroyed) {
                                    return@runOnUiThread
                                }
                                bar.progress = pct
                                tv.text = if (pct >= 100) {
                                    "下载完成，正在校验签名…"
                                } else {
                                    "已下载 $pct%"
                                }
                            }
                        },
                        { cancelled }
                    )
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
            if (progress.isShowing) progress.dismiss()
            if (cancelled || activity.isFinishing || activity.isDestroyed) return@launch
            result.onSuccess { file -> install(file, info) }
                .onFailure { error ->
                    AlertDialog.Builder(activity)
                        .setTitle("下载失败")
                        .setMessage((error.message ?: "未知错误") + "\n\n可以改用网页下载。")
                        .setPositiveButton("网页下载") { _, _ -> openPage(info.pageUrl) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
        }
    }

    private fun install(file: File, info: UpdateChecker.Info) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("apk", uri)
            }
            activity.startActivity(intent)
        } catch (t: Throwable) {
            AlertDialog.Builder(activity)
                .setTitle("无法启动安装器")
                .setMessage((t.message ?: "未知错误") + "\n\n请改用网页下载。")
                .setPositiveButton("网页下载") { _, _ -> openPage(info.pageUrl) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun openPage(url: String) {
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            toast("无法打开浏览器")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var checking = false
    }
}
