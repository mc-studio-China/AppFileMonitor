package com.buxingjie.appfilemonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 自身升级完成后清掉 cache/update 里残留的安装包。
 */
class UpdateCleanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent?.action != Intent.ACTION_PACKAGE_REPLACED
        ) return
        val dir = java.io.File(context.cacheDir, "update")
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
