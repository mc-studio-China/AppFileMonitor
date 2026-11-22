package com.buxingjie.appfilemonitor.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * 文件浏览与增删改操作。
 *
 * 免 Root（Shizuku Shell）在部分机型上对 /storage/emulated/0/Android/data
 * 只有读权限，因此所有写操作会依次尝试等价路径（/storage/emulated/0、
 * /sdcard、/data/media/0），并对结果做存在性校验，避免“报成功但没生效”。
 */
object Fs {

    data class Entry(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long
    )

    fun isPrivate(path: String): Boolean =
        path.startsWith("/data/data/") || path.startsWith("/data/user/0/")

    fun list(pkg: String, dir: String): Pair<List<Entry>, String?> {
        val cmd = "find ${Shell.quote(dir)} -maxdepth 1 " +
            "-exec stat -c '%F|%s|%Y|%n' {} + 2>/dev/null"
        val full = if (isPrivate(dir)) Shell.wrapPrivate(pkg, cmd) else cmd
        val r = Shell.exec(full, 45000)
        if (!r.ok && r.out.isBlank()) {
            return Pair(emptyList(), "无法读取目录：${r.message().take(160)}")
        }
        val cleanDir = dir.trimEnd('/').ifEmpty { "/" }
        val list = StatParser.parse(r.out, dir)
            .filter { it.path.trimEnd('/') != cleanDir }
            .map {
                Entry(
                    it.path,
                    it.path.substringAfterLast('/'),
                    it.isDir,
                    it.size,
                    it.mtime
                )
            }
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        return Pair(list, null)
    }

    /** 同一路径在免 Root 下的几种等价写法 */
    private fun variants(path: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(path)
        val reps = listOf(
            "/storage/emulated/0" to "/data/media/0",
            "/storage/emulated/0" to "/sdcard",
            "/sdcard" to "/storage/emulated/0",
            "/sdcard" to "/data/media/0",
            "/data/media/0" to "/storage/emulated/0",
            "/mnt/user/0/primary" to "/storage/emulated/0"
        )
        for ((from, to) in reps) {
            if (path.startsWith(from)) out.add(to + path.removePrefix(from))
        }
        return out.toList()
    }

    private fun parentOf(path: String): String =
        path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }

    private fun exists(path: String): Boolean =
        Shell.exec("test -e ${Shell.quote(path)} && echo yes", 15000).out.contains("yes")

    fun mkdir(pkg: String, path: String): Shell.Result {
        var last = Shell.Result(-1, "", "未执行")
        for (p in variants(path)) {
            last = execFor(pkg, "mkdir -p -- ${Shell.quote(p)}", p)
            if (exists(p)) {
                fixOwnerSafe(pkg, p)
                return Shell.Result(0, p, "")
            }
            if (canDirect(p) && File(p).mkdirs()) return Shell.Result(0, p, "")
        }
        return last.enrich(pkg, path)
    }

    fun delete(pkg: String, path: String): Shell.Result {
        var last = Shell.Result(-1, "", "未执行")
        for (p in variants(path)) {
            execFor(pkg, "rm -rf -- ${Shell.quote(p)}", p)
            if (!exists(p)) return Shell.Result(0, p, "")
            last = execFor(pkg, "rm -rf -- ${Shell.quote(p)}", p)
        }
        if (canDirect(path)) {
            for (p in variants(path)) {
                if (directDelete(p) && !directExists(p)) return Shell.Result(0, p, "")
            }
        }
        return last.enrich(pkg, path)
    }

    fun rename(pkg: String, src: String, dst: String): Shell.Result {
        var last = Shell.Result(-1, "", "未执行")
        for (p in variants(src)) {
            for (q in variants(dst)) {
                last = execFor(pkg, "mv -- ${Shell.quote(p)} ${Shell.quote(q)}", p, q)
                if (exists(q)) {
                    fixOwnerSafe(pkg, q)
                    return Shell.Result(0, q, "")
                }
                if (canDirect(p) && canDirect(q) && copyRecursive(File(p), File(q))) {
                    return Shell.Result(0, q, "")
                }
            }
        }
        return last.enrich(pkg, src)
    }

    fun copy(pkg: String, src: String, dst: String): Shell.Result {
        var last = Shell.Result(-1, "", "未执行")
        for (p in variants(src)) {
            for (q in variants(dst)) {
                ensureParent(pkg, q)
                last = execFor(pkg, "cp -a -- ${Shell.quote(p)} ${Shell.quote(q)}", p, q)
                if (exists(q)) {
                    fixOwnerSafe(pkg, q)
                    return Shell.Result(0, q, "")
                }
            }
        }
        return last.enrich(pkg, src)
    }

    fun move(pkg: String, src: String, dst: String): Shell.Result = rename(pkg, src, dst)

    private fun ensureParent(pkg: String, dest: String) {
        val parent = parentOf(dest)
        execFor(pkg, "mkdir -p -- ${Shell.quote(parent)}", parent)
    }

    fun importStream(pkg: String, dest: String, input: InputStream): Shell.Result {
        // 先把数据落到应用缓存，避免 shell 写入失败（EPIPE）后输入流已耗尽、无法换路径重试
        val base = I18n.ctx?.cacheDir
        if (base == null) return Shell.Result(-1, "", "缓存目录不可用")
        val buf = try {
            val dir = File(base, "import_cache")
            dir.mkdirs()
            File.createTempFile("imp", ".tmp", dir)
        } catch (t: Throwable) {
            return Shell.Result(-1, "", "缓存写入失败：" + (t.message ?: t.toString()))
        }
        try {
            input.use { ins -> FileOutputStream(buf).use { os -> ins.copyTo(os) } }
        } catch (t: Throwable) {
            runCatching { buf.delete() }
            return Shell.Result(-1, "", "读取来源失败：" + (t.message ?: t.toString()))
        }

        var last = Shell.Result(-1, "", "未执行")
        try {
            for (p in variants(dest)) {
                ensureParent(pkg, p)
                execFor(pkg, "cat ${Shell.quote(buf.absolutePath)} > ${Shell.quote(p)}", p)
                if (exists(p)) {
                    fixOwnerSafe(pkg, p)
                    return Shell.Result(0, p, "")
                }
                if (canDirect(p) && copyRecursive(buf, File(p))) {
                    return Shell.Result(0, p, "")
                }
                last = execFor(pkg, "cat ${Shell.quote(buf.absolutePath)} > ${Shell.quote(p)}", p)
            }
            return last.enrich(pkg, dest)
        } finally {
            runCatching { buf.delete() }
        }
    }

    private fun writeStream(pkg: String, dest: String, input: InputStream): Shell.Result {
        val cmd = if (isPrivate(dest)) Shell.wrapPrivate(pkg, "cat > ${Shell.quote(dest)}")
        else "cat > ${Shell.quote(dest)}"
        val process = Shell.startProcess(cmd)
            ?: return Shell.Result(-1, "", "无法启动 shell 进程")
        val errBuf = ByteArrayOutputStream()
        val errThread = thread(isDaemon = true) {
            runCatching { process.errorStream.use { it.copyTo(errBuf) } }
        }
        try {
            process.outputStream.use { os -> input.use { ins -> ins.copyTo(os) } }
        } catch (t: Throwable) {
            runCatching { process.destroy() }
            return Shell.Result(-1, "", "写入失败：" + (t.message ?: t.toString()))
        }
        val code = try {
            process.waitFor()
        } catch (t: Throwable) {
            -1
        }
        errThread.join(2000)
        return Shell.Result(code, "", errBuf.toString("UTF-8").trim())
    }

    private fun execFor(pkg: String, cmd: String, vararg paths: String): Shell.Result {
        val needsPrivate = paths.any { isPrivate(it) }
        val full = if (needsPrivate) Shell.wrapPrivate(pkg, cmd) else cmd
        return Shell.exec(full, 90000)
    }


    // ---- 应用自身身份直连兜底（需“所有文件访问”权限，覆盖 /storage 共享存储） ----

    private fun canDirect(path: String): Boolean =
        path.startsWith("/storage/emulated/0") || path.startsWith("/sdcard")

    private fun directDelete(path: String): Boolean {
        val f = File(path)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    private fun directExists(path: String): Boolean = File(path).exists()

    private fun copyRecursive(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            src.listFiles()?.forEach { c ->
                if (!copyRecursive(c, File(dst, c.name))) return false
            }
            return true
        }
        dst.parentFile?.mkdirs()
        return runCatching {
            src.inputStream().use { ins ->
                FileOutputStream(dst).use { os -> ins.copyTo(os) }
            }
            true
        }.getOrDefault(false)
    }

    private fun fixOwnerSafe(pkg: String, path: String) {
        runCatching { Shell.fixOwner(pkg, path, true) }
    }

    /** 失败时附带执行身份与父目录信息，方便定位权限问题 */
    private fun Shell.Result.enrich(pkg: String, path: String): Shell.Result {
        val parent = parentOf(path)
        val who = Shell.exec("id -u 2>/dev/null; ls -ld ${Shell.quote(parent)} 2>&1", 15000)
            .out.trim().replace("\n", " | ").take(180)
        val msg = message()
        return Shell.Result(code, out, "$msg\n(uid/父目录: $who)")
    }
}
