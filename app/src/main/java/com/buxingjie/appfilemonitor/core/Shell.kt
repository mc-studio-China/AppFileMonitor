package com.buxingjie.appfilemonitor.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 命令执行层：Root(su) / Shizuku(Root) / Shizuku(Shell) / 本应用。
 * 所有方法都应在后台线程调用。
 */
object Shell {

    enum class Mode(val label: String) {
        ROOT("Root"),
        SHIZUKU_ROOT("Shizuku (Root)"),
        SHIZUKU_SHELL("Shizuku (Shell)"),
        NONE("未授权")
    }

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0

        fun message(): String =
            (if (ok) out else err.ifBlank { out }).trim().ifBlank { "exit=$code" }
    }

    @Volatile
    var mode: Mode = Mode.NONE
        private set

    @Volatile
    var shizukuUid: Int = -1
        private set

    private val execLock = Any()
    private val debugCache = ConcurrentHashMap<String, Boolean>()

    fun isRoot(): Boolean = mode == Mode.ROOT || mode == Mode.SHIZUKU_ROOT

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun awaitBinder(timeoutMs: Long = 2000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                if (Shizuku.getBinder() != null) return true
            } catch (_: Throwable) {
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    fun requestPermission(requestCode: Int) {
        if (!shizukuRunning()) throw IllegalStateException("Shizuku 服务未运行，请先启动 Shizuku")
        Shizuku.requestPermission(requestCode)
    }

    /** 刷新当前执行模式（不主动弹 Root 授权）。 */
    fun refresh(ctx: Context): Mode {
        awaitBinder(600)
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val useRoot = sp.getBoolean("root_ok", false)
        var m = Mode.NONE
        if (useRoot) {
            m = Mode.ROOT
            shizukuUid = -1
        } else if (shizukuGranted()) {
            shizukuUid = try {
                Shizuku.getUid()
            } catch (t: Throwable) {
                -1
            }
            m = if (shizukuUid == 0) Mode.SHIZUKU_ROOT else Mode.SHIZUKU_SHELL
        }
        mode = m
        return m
    }

    /** 主动检测 su（可能弹出 Root 授权框）。 */
    fun detectRoot(ctx: Context): Boolean {
        val r = execRaw(listOf("su", "-c", "id"), 10000)
        val ok = r.ok && r.out.contains("uid=0")
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("root_ok", ok).apply()
        if (ok) {
            mode = Mode.ROOT
            shizukuUid = 0
        }
        return ok
    }

    fun disableRoot(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("root_ok", false).apply()
        if (mode == Mode.ROOT) mode = Mode.NONE
    }

    fun exec(cmd: String, timeoutMs: Long = 60000): Result = synchronized(execLock) {
        val p = try {
            createProcess(cmd)
        } catch (t: Throwable) {
            return Result(-1, "", t.message ?: t.toString())
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val t1 = thread(isDaemon = true) {
            runCatching { p.inputStream.use { it.copyTo(out) } }
        }
        val t2 = thread(isDaemon = true) {
            runCatching { p.errorStream.use { it.copyTo(err) } }
        }
        val finished = try {
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!finished) {
            runCatching { p.destroy() }
            t1.join(1000)
            t2.join(1000)
            return Result(-2, out.toString("UTF-8"), "命令超时（${timeoutMs}ms）")
        }
        t1.join(3000)
        t2.join(3000)
        val code = try {
            p.exitValue()
        } catch (t: Throwable) {
            -1
        }
        Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /** 启动一个可供写入 stdin 的 shell 进程（用于导入文件）。 */
    fun startProcess(cmd: String): Process? = try {
        createProcess(cmd)
    } catch (t: Throwable) {
        null
    }

    fun isDebuggable(pkg: String): Boolean = debugCache.getOrPut(pkg) {
        val r = exec("run-as " + pkg + " id", 10000)
        r.ok && r.out.contains("uid=")
    }

    fun privateSupported(pkg: String): Boolean =
        isRoot() || (mode == Mode.SHIZUKU_SHELL && isDebuggable(pkg))

    /**
     * 私有目录命令适配：
     * - Root / Shizuku(Root)：直接执行。
     * - Shizuku(Shell) + debuggable：用 run-as 进入应用身份，并把绝对路径改成相对路径。
     */
    fun wrapPrivate(pkg: String, cmd: String): String {
        if (isRoot()) return cmd
        if (mode == Mode.SHIZUKU_SHELL && isDebuggable(pkg)) {
            val inner = cmd
                .replace("/data/data/$pkg", ".")
                .replace("/data/user/0/$pkg", ".")
            return "run-as $pkg sh -c " + quote(inner)
        }
        return cmd
    }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun privateRoot(pkg: String): String = "/data/data/$pkg"

    /** Root 下把文件属主改回目标应用，并恢复 SELinux 上下文。 */
    fun fixOwner(pkg: String, path: String, recursive: Boolean) {
        if (!isRoot()) return
        val st = exec("stat -c '%u:%g' " + quote(privateRoot(pkg)), 10000)
        if (!st.ok) return
        val owner = st.out.trim().lineSequence().firstOrNull()?.trim() ?: return
        if (!Regex("\\d+:\\d+").matches(owner)) return
        exec(
            "chown -R $owner ${quote(path)} 2>/dev/null; " +
                "chmod -R u+rwX ${quote(path)} 2>/dev/null; " +
                "restorecon -R ${quote(path)} 2>/dev/null || true",
            60000
        )
    }

    private fun createProcess(cmd: String): Process = when (mode) {
        Mode.ROOT -> ProcessBuilder("su", "-c", cmd).start()
        Mode.SHIZUKU_ROOT, Mode.SHIZUKU_SHELL -> {
            val binder = Shizuku.getBinder()
                ?: throw IllegalStateException("Shizuku 服务不可用")
            val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            val remote = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
            RemoteProcess(remote)
        }

        Mode.NONE -> ProcessBuilder("sh", "-c", cmd).start()
    }

    private fun execRaw(args: List<String>, timeoutMs: Long): Result {
        val p = try {
            ProcessBuilder(args).start()
        } catch (t: Throwable) {
            return Result(-1, "", t.message ?: t.toString())
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val t1 = thread(isDaemon = true) { runCatching { p.inputStream.use { it.copyTo(out) } } }
        val t2 = thread(isDaemon = true) { runCatching { p.errorStream.use { it.copyTo(err) } } }
        val finished = try {
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!finished) {
            runCatching { p.destroy() }
            t1.join(500)
            t2.join(500)
            return Result(-2, out.toString("UTF-8"), "命令超时")
        }
        t1.join(2000)
        t2.join(2000)
        val code = try {
            p.exitValue()
        } catch (t: Throwable) {
            -1
        }
        return Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /** 包装 Shizuku 服务返回的远程进程。 */
    private class RemoteProcess(private val remote: IRemoteProcess) : Process() {
        private val out: OutputStream by lazy {
            ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
        }
        private val inp: InputStream by lazy {
            ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        }

        override fun getOutputStream(): OutputStream = out

        override fun getInputStream(): InputStream = inp

        override fun getErrorStream(): InputStream =
            ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

        override fun waitFor(): Int = remote.waitFor()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            remote.waitForTimeout(timeout, unit.name)

        override fun exitValue(): Int = remote.exitValue()

        override fun destroy() {
            runCatching { remote.destroy() }
        }
    }
}
