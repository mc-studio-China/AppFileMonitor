package com.buxingjie.appfilemonitor.core

import android.content.Context
import java.util.Locale

class Scanner(private val ctx: Context) {

    private val db = DbHelper.get(ctx)

    data class Result(
        val added: Int,
        val deleted: Int,
        val modified: Int,
        val total: Int,
        val baselineRoots: Int,
        val errors: List<String>,
        val durationMs: Long
    )

    private data class RootDef(
        val id: String,
        val label: String,
        val path: String,
        val privateRoot: Boolean,
        val maxDepth: Int?
    )

    fun scan(pkg: String, includeDownload: Boolean): Result {
        val started = System.currentTimeMillis()
        var added = 0
        var deleted = 0
        var modified = 0
        var total = 0
        var baselines = 0
        val errors = ArrayList<String>()

        val roots = ArrayList<RootDef>()
        if (Shell.privateSupported(pkg)) {
            roots.add(RootDef("private", "应用私有数据", Shell.privateRoot(pkg), true, null))
        } else {
            errors.add("私有数据不可访问：需要 Root，或目标应用为 debuggable（可用 run-as）")
        }
        roots.add(
            RootDef(
                "ext_data", "外部数据",
                "/storage/emulated/0/Android/data/$pkg", false, null
            )
        )
        roots.add(
            RootDef(
                "ext_media", "外部媒体",
                "/storage/emulated/0/Android/media/$pkg", false, 8
            )
        )
        if (includeDownload) {
            roots.add(
                RootDef(
                    "download", "共享下载目录",
                    "/storage/emulated/0/Download", false, 3
                )
            )
        }

        val now = System.currentTimeMillis()
        for (root in roots) {
            val entries = try {
                scanRoot(pkg, root, errors)
            } catch (t: Throwable) {
                errors.add(root.label + "：" + (t.message ?: t.toString()))
                null
            } ?: continue

            total += entries.size
            val snapshots = entries.map {
                DbHelper.Snap(it.path, it.isDir, it.size, it.mtime)
            }
            val previous = db.loadSnapshot(pkg, root.id)

            if (!db.hasRootMeta(pkg, root.id)) {
                db.applySnapshot(pkg, root.id, snapshots, emptyList(), now)
                baselines++
                continue
            }

            val current = HashMap<String, StatParser.Entry>(entries.size)
            for (e in entries) current[e.path] = e

            val events = ArrayList<DbHelper.Event>()
            for ((path, e) in current) {
                val old = previous[path]
                if (old == null) {
                    events.add(
                        DbHelper.Event(
                            0, root.id, path, DbHelper.ADDED, e.isDir,
                            e.size, e.mtime, now
                        )
                    )
                    added++
                } else if (!e.isDir && (old.size != e.size || old.mtime != e.mtime)) {
                    events.add(
                        DbHelper.Event(
                            0, root.id, path, DbHelper.MODIFIED, false,
                            e.size, e.mtime, now
                        )
                    )
                    modified++
                }
            }
            for ((path, old) in previous) {
                if (!current.containsKey(path)) {
                    events.add(
                        DbHelper.Event(
                            0, root.id, path, DbHelper.DELETED, old.isDir,
                            old.size, old.mtime, now
                        )
                    )
                    deleted++
                }
            }
            db.applySnapshot(pkg, root.id, snapshots, events, now)
        }

        return Result(
            added, deleted, modified, total, baselines, errors,
            System.currentTimeMillis() - started
        )
    }

    private fun scanRoot(
        pkg: String,
        root: RootDef,
        errors: MutableList<String>
    ): List<StatParser.Entry>? {
        val depth = root.maxDepth?.let { "-maxdepth $it " } ?: ""
        val cmd = "if [ -e ${Shell.quote(root.path)} ]; then " +
            "find ${Shell.quote(root.path)} ${depth}-exec stat -c '%F|%s|%Y|%n' {} + 2>/dev/null; " +
            "else echo __MISSING__; fi"
        val full = if (root.privateRoot) Shell.wrapPrivate(pkg, cmd) else cmd
        val r = Shell.exec(full, 180000)
        if (r.out.contains("__MISSING__")) return null
        if (!r.ok && r.out.isBlank()) {
            errors.add("${root.label}：读取出错（${r.message().take(120)}）")
            return null
        }
        if (r.out.isBlank()) return emptyList()
        return StatParser.parse(r.out, root.path)
    }
}
