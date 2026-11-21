package com.buxingjie.appfilemonitor.core

import android.os.Build

/**
 * 免 Root 下载来源查询：
 * - MediaStore.Downloads 的 owner_package_name（Android 10+）
 * - DownloadManager 的 notificationpackage（部分应用）
 * 只能查到写入共享存储/系统下载器的文件，查不到应用私有目录内的下载。
 */
object DownloadFinder {

    data class Item(val title: String, val path: String, val source: String)

    data class Found(val items: List<Item>, val error: String?)

    private val fieldRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun find(pkg: String): Found {
        val items = LinkedHashMap<String, Item>()
        val errors = ArrayList<String>()

        // owner_package_name / relative_path 从 Android 10 (API 29) 才有。
        if (Build.VERSION.SDK_INT >= 29) {
            val media = Shell.exec(
                "content query --uri content://media/external/downloads " +
                    "--projection _data:display_name:owner_package_name:relative_path 2>&1",
                30000
            )
            parse(media) { line ->
                field(line, "owner_package_name") == pkg
            }?.let { rows ->
                for (line in rows) {
                    val path = mediaPath(line) ?: continue
                    val title = field(line, "display_name") ?: path.substringAfterLast('/')
                    items[path] = Item(title, path, "MediaStore")
                }
            }
            if (!media.ok) {
                errors.add("MediaStore 查询失败：${media.message().take(120)}")
            }
        }

        val dm = Shell.exec(
            "content query --uri content://downloads/all_downloads " +
                "--projection _data:title:notificationpackage:status 2>&1",
            30000
        )
        parse(dm) { line ->
            field(line, "notificationpackage") == pkg
        }?.let { rows ->
            for (line in rows) {
                val path = field(line, "_data") ?: continue
                if (!path.startsWith("/")) continue
                val title = field(line, "title") ?: path.substringAfterLast('/')
                items.putIfAbsent(path, Item(title, path, "DownloadManager"))
            }
        }
        if (!dm.ok) {
            errors.add("DownloadManager 查询失败：${dm.message().take(120)}")
        }

        val result = items.values.toList()
        return Found(
            result.take(200),
            if (result.isEmpty() && errors.isNotEmpty()) errors.joinToString("\n") else null
        )
    }

    private fun parse(
        r: Shell.Result,
        match: (String) -> Boolean
    ): List<String>? {
        val text = r.out.ifBlank { r.err }
        if (text.isBlank()) return null
        val rows = ArrayList<String>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("Row:")) continue
            if (!match(line)) continue
            rows.add(line)
        }
        return rows
    }

    private fun mediaPath(line: String): String? {
        field(line, "_data")?.takeIf { it.startsWith("/") }?.let { return it }
        val name = field(line, "display_name") ?: return null
        val rel = field(line, "relative_path")?.trim('/') ?: return null
        return "/storage/emulated/0/$rel/$name"
    }

    private fun field(line: String, key: String): String? {
        val regex = fieldRegexCache.getOrPut(key) {
            Regex(
                "(?:^|[,\\s])" + Regex.escape(key) +
                    "=(.*?)(?=,\\s[A-Za-z_][A-Za-z0-9_.]*=|$)"
            )
        }
        val value = regex.find(line)?.groupValues?.get(1)?.trim() ?: return null
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }
}
