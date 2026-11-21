package com.buxingjie.appfilemonitor.core

/**
 * 解析 `stat -c '%F|%s|%Y|%n'` 的输出。
 */
object StatParser {

    data class Entry(
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long
    )

    fun parse(output: String, rootPath: String): List<Entry> {
        if (output.isBlank()) return emptyList()
        val result = ArrayList<Entry>()
        for (line in output.lineSequence()) {
            if (line == "__MISSING__") continue
            if (!line.contains('|')) continue
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) continue
            val type = parts[0].lowercase()
            if (type.contains("link") || type.contains("socket") ||
                type.contains("fifo") || type.contains("special")
            ) continue
            val size = parts[1].toLongOrNull() ?: 0L
            val mtime = parts[2].toLongOrNull() ?: 0L
            val raw = parts[3]
            if (raw.isBlank()) continue
            val abs = when {
                raw.startsWith("/") -> raw
                raw == "." -> rootPath
                raw.startsWith("./") -> rootPath.trimEnd('/') + raw.substring(1)
                else -> rootPath.trimEnd('/') + "/" + raw
            }
            result.add(Entry(abs, type.contains("directory"), size, mtime))
        }
        return result
    }
}
