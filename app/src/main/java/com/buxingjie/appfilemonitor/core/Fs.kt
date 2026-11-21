package com.buxingjie.appfilemonitor.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * 文件浏览与增删改操作。
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

    fun mkdir(pkg: String, path: String): Shell.Result =
        execFor(pkg, "mkdir -p ${Shell.quote(path)}", path)

    fun delete(pkg: String, path: String): Shell.Result =
        execFor(pkg, "rm -rf ${Shell.quote(path)}", path)

    fun rename(pkg: String, src: String, dst: String): Shell.Result {
        val r = execFor(pkg, "mv ${Shell.quote(src)} ${Shell.quote(dst)}", src, dst)
        if (r.ok) Shell.fixOwner(pkg, dst, false)
        return r
    }

    fun copy(pkg: String, src: String, dst: String): Shell.Result {
        val r = execFor(pkg, "cp -a ${Shell.quote(src)} ${Shell.quote(dst)}", src, dst)
        if (r.ok) Shell.fixOwner(pkg, dst, true)
        return r
    }

    fun move(pkg: String, src: String, dst: String): Shell.Result {
        val r = execFor(pkg, "mv ${Shell.quote(src)} ${Shell.quote(dst)}", src, dst)
        if (r.ok) Shell.fixOwner(pkg, dst, true)
        return r
    }

    fun importStream(pkg: String, dest: String, input: InputStream): Shell.Result {
        val full = if (isPrivate(dest)) {
            Shell.wrapPrivate(pkg, "cat > ${Shell.quote(dest)}")
        } else {
            "cat > ${Shell.quote(dest)}"
        }
        val process = Shell.startProcess(full)
            ?: return Shell.Result(-1, "", "无法启动 shell 进程")
        val errBuf = ByteArrayOutputStream()
        val errThread = thread(isDaemon = true) {
            runCatching { process.errorStream.use { it.copyTo(errBuf) } }
        }
        try {
            process.outputStream.use { os ->
                input.use { ins -> ins.copyTo(os) }
            }
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
        if (code == 0) Shell.fixOwner(pkg, dest, false)
        return Shell.Result(code, "", errBuf.toString("UTF-8"))
    }

    private fun execFor(pkg: String, cmd: String, vararg paths: String): Shell.Result {
        val needsPrivate = paths.any { isPrivate(it) }
        val full = if (needsPrivate) Shell.wrapPrivate(pkg, cmd) else cmd
        return Shell.exec(full, 90000)
    }
}
