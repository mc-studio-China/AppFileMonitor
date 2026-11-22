package com.buxingjie.appfilemonitor.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 读取 GitHub Release 的最新版本信息。
 * API 优先走镜像，避免国内直连 api.github.com 超时或限流。
 */
object UpdateChecker {

    private const val REPO = "mc-studio-China/AppFileMonitor"

    data class Info(
        val version: String,
        val tag: String,
        val changelog: String,
        val pageUrl: String,
        val apkUrl: String?,
        val apkSize: Long
    )

    fun fetch(route: Int = 0): Result<Info> {
        var lastError: String? = null
        for (url in UpdateRoutes.apiUrls(route, REPO)) {
            try {
                val info = parse(get(url))
                if (info != null) return Result.success(info)
                lastError = "返回数据无效"
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
        return Result.failure(IllegalStateException(lastError ?: "无法连接 GitHub"))
    }

    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String): List<Int> = v.removePrefix("v")
            .substringBefore('-')
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AppFileMonitor")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): Info? {
        val json = JSONObject(text)
        val tag = json.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val version = tag.removePrefix("v").trim()
        val page = json.optString("html_url").ifBlank {
            "https://github.com/$REPO/releases"
        }
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").ifBlank { null }
                apkSize = asset.optLong("size", 0L)
                if (name.contains("AppFileMonitor", ignoreCase = true)) break
            }
        }
        return Info(
            version = version,
            tag = tag,
            changelog = json.optString("body").trim(),
            pageUrl = page,
            apkUrl = apkUrl,
            apkSize = apkSize
        )
    }
}
