package com.buxingjie.appfilemonitor.core

import android.os.Build
import android.os.Environment
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

    /** 路径是否存在（多等价路径任一存在即算存在） */
    fun existsPath(path: String): Boolean = variants(path).any { exists(it) }

    fun mkdir(pkg: String, path: String): Shell.Result {
        var last = Shell.Result(-1, "", "未执行")
        for (p in variants(path)) {
            if (directUsable(p) && (File(p).isDirectory || File(p).mkdirs())) {
                return Shell.Result(0, p, "")
            }
            last = execFor(pkg, "mkdir -p -- ${Shell.quote(p)}", p)
            if (exists(p)) {
                fixOwnerSafe(pkg, p)
                return Shell.Result(0, p, "")
            }
        }
        return last.enrich(pkg, path)
    }

    fun delete(pkg: String, path: String): Shell.Result {
        var last = Shell.Result(-1, "", "未执行")
        for (p in variants(path)) {
            if (directUsable(p) && directDelete(p) && !directExists(p)) {
                return Shell.Result(0, p, "")
            }
            last = execFor(pkg, "rm -rf -- ${Shell.quote(p)}", p)
            if (!exists(p)) return Shell.Result(0, p, "")
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
        // 数据先落到「外部缓存目录」：该目录 shell 读得到，应用自己也读得到
        val ctx = I18n.ctx ?: return Shell.Result(-1, "", "上下文不可用")
        val dir = (ctx.getExternalCacheDir() ?: ctx.cacheDir)?.let { File(it, "import_cache") }
            ?: return Shell.Result(-1, "", "缓存目录不可用")
        val buf = try {
            dir.mkdirs()
            File.createTempFile("imp", ".tmp", dir)
        } catch (t: Throwable) {
            return Shell.Result(-1, "", "缓存写入失败：" + (t.message ?: t.toString()))
        }
        val size = try {
            input.use { ins -> FileOutputStream(buf).use { os -> ins.copyTo(os) } }
            buf.length()
        } catch (t: Throwable) {
            runCatching { buf.delete() }
            return Shell.Result(-1, "", "读取来源失败：" + (t.message ?: t.toString()))
        }
        if (size <= 0) {
            runCatching { buf.delete() }
            return Shell.Result(-1, "", "来源文件为空")
        }
        val shellReadable = buf.absolutePath.startsWith("/storage/emulated/0")

        var last = Shell.Result(-1, "", "未执行")
        try {
            for (p in variants(dest)) {
                // 1) 应用身份直写（已授权“所有文件访问”时最稳，也不会破坏原文件）
                if (directUsable(p) && copyRecursive(buf, File(p)) && File(p).length() == size) {
                    return Shell.Result(0, p, "")
                }
                if (!shellReadable) {
                    last = Shell.Result(-1, "", "缓存不可被 shell 读取")
                    continue
                }
                // 2) shell 写临时文件再改名：失败时原文件保持原样，不会变成 0 字节
                val tmp = "$p.part"
                execFor(pkg, "mkdir -p -- ${Shell.quote(parentOf(p))}", parentOf(p))
                last = execFor(
                    pkg,
                    "cp -f ${Shell.quote(buf.absolutePath)} ${Shell.quote(tmp)} && " +
                        "mv -f ${Shell.quote(tmp)} ${Shell.quote(p)}",
                    p
                )
                if (sizeOf(p) == size) {
                    fixOwnerSafe(pkg, p)
                    return Shell.Result(0, p, "")
                }
                execFor(pkg, "rm -f -- ${Shell.quote(tmp)}", p)
            }
            return Shell.Result(
                -1, "",
                (last.message().ifBlank { "写入未生效" }) + "（目标大小校验不一致）"
            ).enrich(pkg, dest)
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

    /** 应用是否持有“所有文件访问”权限（Android 11+ 访问其他应用 Android/data 必需） */
    fun allFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 ||
            (I18n.ctx?.let { Environment.isExternalStorageManager() } ?: false)

    /** 只有“共享存储 + 已授权”才适合用应用自身身份直接操作 */
    private fun directUsable(path: String): Boolean = canDirect(path) && allFilesAccess()

    private fun sizeOf(path: String): Long =
        Shell.exec("wc -c < ${Shell.quote(path)} 2>/dev/null", 15000)
            .out.trim().toLongOrNull() ?: -1L

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
