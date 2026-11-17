package com.ztc1997.nocturne.hook

import android.content.Context
import android.provider.Settings

/** 勿扰 / 睡眠模式检测。仅在目标进程使用。 */
object DndState {

    /** 当前勿扰模式是否开启（zen_mode != 0）。 */
    fun isActive(): Boolean = runCatching {
        val ctx = currentAppContext() ?: return false
        Settings.Global.getInt(ctx.contentResolver, "zen_mode", 0) != 0
    }.getOrDefault(false)

    /** 通过 ActivityThread 取当前进程 Application Context。 */
    fun currentAppContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val m = at.getDeclaredMethod("currentApplication")
        m.isAccessible = true
        m.invoke(null) as? Context
    }.getOrNull()
}
