package com.buxingjie.appfilemonitor.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新下载：镜像优先，下载后校验证书与当前应用一致，避免镜像被篡改。
 */
object UpdateDownloader {

    private val MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/"
    )

    fun download(
        context: Context,
        info: UpdateChecker.Info,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Result<File> {
        val rawUrl = info.apkUrl
            ?: return Result.failure(IOException("该版本没有 APK 附件"))

        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "AppFileMonitor-${info.version}.apk")
        val urls = (MIRRORS.map { it + rawUrl } + rawUrl).distinct()
        var lastError: String? = null

        for ((index, url) in urls.withIndex()) {
            if (isCancelled()) return Result.failure(CancellationException("已取消"))
            val tmp = File(dir, "download-$index.tmp")
            tmp.delete()
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("User-Agent", "AppFileMonitor")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        lastError = "HTTP $code"
                        continue
                    }
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                if (isCancelled()) throw CancellationException("已取消")
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) {
                                    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                if (tmp.length() < 100 * 1024) {
                    lastError = "下载内容不是有效 APK"
                    tmp.delete()
                    continue
                }
                if (!verifySignature(context, tmp)) {
                    lastError = "安装包签名校验失败"
                    tmp.delete()
                    continue
                }
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                onProgress(100)
                return Result.success(target)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t.message ?: t.javaClass.simpleName
                tmp.delete()
            }
        }
        return Result.failure(IOException(lastError ?: "所有镜像均下载失败"))
    }

    private fun verifySignature(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return@runCatching false
        val installed = pm.getPackageInfo(context.packageName, flags)
        val a = signerHashes(archive)
        val b = signerHashes(installed)
        a.isNotEmpty() && a == b
    }.getOrDefault(false)

    private fun signerHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()
        return signatures.mapTo(HashSet()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
