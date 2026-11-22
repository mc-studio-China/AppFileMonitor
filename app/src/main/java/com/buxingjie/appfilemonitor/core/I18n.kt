package com.buxingjie.appfilemonitor.core

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import java.util.Locale
import org.json.JSONObject

/**
 * 轻量运行时多语言：以中文原文为 key，从 assets/i18n/<code>.json 取译文。
 * 不依赖系统语言，切换后立即生效（调用 Activity.recreate 即可）。
 */
object I18n {

    data class Lang(val code: String, val label: String, val rtl: Boolean = false)

    const val AUTO = "auto"

    val LANGS = listOf(
        Lang("zh-CN", "简体中文"),
        Lang("zh-TW", "繁體中文"),
        Lang("en", "English"),
        Lang("es", "Español"),
        Lang("pt-BR", "Português (BR)"),
        Lang("ar", "العربية", rtl = true),
        Lang("hi", "हिन्दी"),
        Lang("ru", "Русский"),
        Lang("ja", "日本語"),
        Lang("ko", "한국어"),
        Lang("de", "Deutsch"),
        Lang("fr", "Français"),
        Lang("it", "Italiano"),
        Lang("tr", "Türkçe"),
        Lang("vi", "Tiếng Việt"),
        Lang("in", "Bahasa Indonesia")
    )

    private var loadedCode: String? = null
    private var map: Map<String, String> = emptyMap()
    private var templates: List<Pair<Regex, String>> = emptyList()

    fun labelOf(code: String): String =
        if (code == AUTO) "跟随系统" else LANGS.firstOrNull { it.code == code }?.label ?: "跟随系统"

    fun isRtl(ctx: Context): Boolean = LANGS.firstOrNull { it.code == resolve(ctx) }?.rtl == true

    /** 生效的语言代码（auto 时跟随系统） */
    fun resolve(ctx: Context): String {
        val chosen = Prefs.lang(ctx)
        if (chosen != AUTO && LANGS.any { it.code == chosen }) return chosen
        val sys = systemCode()
        return LANGS.firstOrNull { it.code == sys }?.code ?: "zh-CN"
    }

    private fun systemCode(): String {
        val l = if (Build.VERSION.SDK_INT >= 24)
            Locale.getDefault() else Locale.getDefault()
        val lang = l.language.lowercase()
        return when (lang) {
            "zh" -> if (l.country.uppercase() in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "pt" -> "pt-BR"
            "id" -> "in"
            else -> lang
        }
    }

    fun set(ctx: Context, code: String) {
        Prefs.setLang(ctx, code)
    }

    fun t(ctx: Context, s: String): String {
        ensure(ctx)
        map[s]?.let { return it }
        if (templates.isNotEmpty()) {
            for ((re, value) in templates) {
                val m = re.matchEntire(s) ?: continue
                var out = value
                for (i in 1..m.groupValues.size - 1) {
                    out = out.replace("{${i - 1}}", m.groupValues[i])
                }
                return out
            }
        }
        return s
    }

    fun t(s: String): String {
        val ctx = appContext ?: return s
        return t(ctx, s)
    }

    private var appContext: Context? = null

    /** 供其他模块取应用上下文（如文件操作缓存目录） */
    val ctx: Context?
        get() = appContext

    fun install(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** 遍历视图树，翻译所有 TextView 的 text / hint */
    fun apply(root: View?) {
        root ?: return
        val ctx = root.context
        walk(root, ctx)
    }

    private fun walk(v: View, ctx: Context) {
        if (v is TextView) {
            val text = v.text?.toString()
            if (!text.isNullOrEmpty()) {
                val tr = t(ctx, text)
                if (tr != text) v.text = tr
            }
            v.hint?.toString()?.let { h ->
                val tr = t(ctx, h)
                if (tr != h) v.hint = tr
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), ctx)
        }
    }

    /** 视图被重新 inflate/更新后再刷一次（延后一帧） */
    fun applyLater(root: View?) {
        root ?: return
        root.post { apply(root) }
    }

    private fun ensure(ctx: Context) {
        val code = resolve(ctx)
        if (loadedCode == code) return
        loadedCode = code
        val text = runCatching {
            ctx.assets.open("i18n/$code.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (text == null) {
            map = emptyMap()
            templates = emptyList()
            return
        }
        val obj = JSONObject(text)
        val m = LinkedHashMap<String, String>()
        obj.keys().forEach { k -> m[k] = obj.optString(k) }
        map = m
        templates = m.entries
            .filter { it.key.contains("{0}") || it.key.contains("{1}") }
            .map { (k, v) ->
                var pattern = Regex.escape(k)
                pattern = pattern.replace("\\{0\\}", "(\\d+)").replace("\\{1\\}", "(\\d+)")
                Regex(pattern) to v
            }
    }
}
