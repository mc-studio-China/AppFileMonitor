package com.buxingjie.appfilemonitor.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "monitor.db", null, 1) {

    data class Snap(val path: String, val isDir: Boolean, val size: Long, val mtime: Long)

    data class Event(
        val id: Long,
        val root: String,
        val path: String,
        val type: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long,
        val detectedAt: Long
    )

    data class RootMeta(
        val root: String,
        val baselineAt: Long,
        val lastScan: Long,
        val count: Int
    )

    companion object {
        const val ADDED = "ADDED"
        const val DELETED = "DELETED"
        const val MODIFIED = "MODIFIED"

        private const val MAX_EVENTS = 30000

        private val ROOT_LABELS = mapOf(
            "private" to "应用私有数据",
            "ext_data" to "外部数据",
            "ext_media" to "外部媒体",
            "download" to "共享下载目录"
        )

        fun rootLabel(id: String): String = ROOT_LABELS[id] ?: id

        @Volatile
        private var instance: DbHelper? = null

        fun get(ctx: Context): DbHelper =
            instance ?: synchronized(this) {
                instance ?: DbHelper(ctx).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE snapshots(pkg TEXT NOT NULL, root TEXT NOT NULL, path TEXT NOT NULL, " +
                "is_dir INTEGER NOT NULL, size INTEGER NOT NULL, mtime INTEGER NOT NULL, " +
                "PRIMARY KEY(pkg, root, path))"
        )
        db.execSQL(
            "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, " +
                "root TEXT NOT NULL, path TEXT NOT NULL, type TEXT NOT NULL, is_dir INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, mtime INTEGER NOT NULL, detected_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_events_pkg ON events(pkg, id DESC)")
        db.execSQL(
            "CREATE TABLE root_meta(pkg TEXT NOT NULL, root TEXT NOT NULL, baseline_at INTEGER NOT NULL, " +
                "last_scan INTEGER NOT NULL, file_count INTEGER NOT NULL, PRIMARY KEY(pkg, root))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    fun loadSnapshot(pkg: String, root: String): HashMap<String, Snap> {
        val map = HashMap<String, Snap>()
        readableDatabase.rawQuery(
            "SELECT path,is_dir,size,mtime FROM snapshots WHERE pkg=? AND root=?",
            arrayOf(pkg, root)
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = Snap(
                    c.getString(0),
                    c.getInt(1) != 0,
                    c.getLong(2),
                    c.getLong(3)
                )
            }
        }
        return map
    }

    fun hasRootMeta(pkg: String, root: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM root_meta WHERE pkg=? AND root=?",
            arrayOf(pkg, root)
        ).use { return it.moveToFirst() }
    }

    fun applySnapshot(
        pkg: String,
        root: String,
        entries: List<Snap>,
        events: List<Event>,
        now: Long
    ) {
        val db = writableDatabase
        val baselineAt = try {
            readableDatabase.rawQuery(
                "SELECT baseline_at FROM root_meta WHERE pkg=? AND root=?",
                arrayOf(pkg, root)
            ).use { if (it.moveToFirst()) it.getLong(0) else now }
        } catch (t: Throwable) {
            now
        }
        db.beginTransaction()
        try {
            db.delete("snapshots", "pkg=? AND root=?", arrayOf(pkg, root))
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO snapshots(pkg,root,path,is_dir,size,mtime) VALUES(?,?,?,?,?,?)"
            )
            for (e in entries) {
                st.clearBindings()
                st.bindString(1, pkg)
                st.bindString(2, root)
                st.bindString(3, e.path)
                st.bindLong(4, if (e.isDir) 1L else 0L)
                st.bindLong(5, e.size)
                st.bindLong(6, e.mtime)
                st.executeInsert()
            }
            if (events.isNotEmpty()) {
                val ev = db.compileStatement(
                    "INSERT INTO events(pkg,root,path,type,is_dir,size,mtime,detected_at) VALUES(?,?,?,?,?,?,?,?)"
                )
                for (x in events) {
                    ev.clearBindings()
                    ev.bindString(1, pkg)
                    ev.bindString(2, x.root)
                    ev.bindString(3, x.path)
                    ev.bindString(4, x.type)
                    ev.bindLong(5, if (x.isDir) 1L else 0L)
                    ev.bindLong(6, x.size)
                    ev.bindLong(7, x.mtime)
                    ev.bindLong(8, x.detectedAt)
                    ev.executeInsert()
                }
            }
            db.execSQL(
                "INSERT OR REPLACE INTO root_meta(pkg,root,baseline_at,last_scan,file_count) VALUES(?,?,?,?,?)",
                arrayOf<Any>(pkg, root, baselineAt, now, entries.size)
            )
            db.delete(
                "events",
                "pkg=? AND id <= (SELECT id FROM events WHERE pkg=? ORDER BY id DESC LIMIT 1 OFFSET $MAX_EVENTS)",
                arrayOf(pkg, pkg)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recentEvents(pkg: String, type: String?): List<Event> {
        val where = if (type == null) "pkg=?" else "pkg=? AND type=?"
        val args = if (type == null) arrayOf(pkg) else arrayOf(pkg, type)
        val list = ArrayList<Event>()
        readableDatabase.rawQuery(
            "SELECT id,root,path,type,is_dir,size,mtime,detected_at FROM events " +
                "WHERE $where ORDER BY id DESC LIMIT 500",
            args
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Event(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getInt(4) != 0,
                        c.getLong(5),
                        c.getLong(6),
                        c.getLong(7)
                    )
                )
            }
        }
        return list
    }

    fun allFilePaths(pkg: String): List<String> {
        val list = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT path FROM snapshots WHERE pkg=? AND is_dir=0",
            arrayOf(pkg)
        ).use { c ->
            while (c.moveToNext()) list.add(c.getString(0))
        }
        return list
    }

    fun rootMetas(pkg: String): List<RootMeta> {
        val list = ArrayList<RootMeta>()
        readableDatabase.rawQuery(
            "SELECT root,baseline_at,last_scan,file_count FROM root_meta WHERE pkg=?",
            arrayOf(pkg)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(RootMeta(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3)))
            }
        }
        return list
    }

    fun clearEvents(pkg: String) {
        writableDatabase.delete("events", "pkg=?", arrayOf(pkg))
    }

    fun clearAll(pkg: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("events", "pkg=?", arrayOf(pkg))
            db.delete("snapshots", "pkg=?", arrayOf(pkg))
            db.delete("root_meta", "pkg=?", arrayOf(pkg))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
