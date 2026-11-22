package com.buxingjie.appfilemonitor.core

import android.content.Context

object Prefs {

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun target(ctx: Context): String? = sp(ctx).getString("target_pkg", null)

    fun setTarget(ctx: Context, pkg: String) {
        sp(ctx).edit().putString("target_pkg", pkg).apply()
    }

    fun monitored(ctx: Context): Set<String> =
        sp(ctx).getStringSet("monitored", emptySet())?.toSet() ?: emptySet()

    fun setMonitored(ctx: Context, pkgs: Set<String>) {
        sp(ctx).edit().putStringSet("monitored", HashSet(pkgs)).apply()
    }

    fun includeDownload(ctx: Context): Boolean =
        sp(ctx).getBoolean("include_download", true)

    fun setIncludeDownload(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("include_download", value).apply()
    }

    fun rootVerified(ctx: Context): Boolean =
        sp(ctx).getBoolean("root_ok", false)

    fun lang(ctx: Context): String =
        sp(ctx).getString("ui_lang", "auto") ?: "auto"

    fun setLang(ctx: Context, code: String) {
        sp(ctx).edit().putString("ui_lang", code).apply()
    }

    fun updateRoute(ctx: Context): Int = sp(ctx).getInt("update_route", 0)

    fun setUpdateRoute(ctx: Context, route: Int) {
        sp(ctx).edit().putInt("update_route", route).apply()
    }

    fun ignoredVersion(ctx: Context): String? =
        sp(ctx).getString("ignored_update", null)

    fun setIgnoredVersion(ctx: Context, version: String) {
        sp(ctx).edit().putString("ignored_update", version).apply()
    }
}
