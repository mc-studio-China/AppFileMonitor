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
}
