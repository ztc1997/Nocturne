package com.ztc1997.nocturne.core

/**
 * 配置快照。一次读取把所有配置项收进一个不可变对象，目标进程里的 hook 回调
 * 只读这一份，避免每次时钟刷新都跨进程读 SharedPreferences。
 *
 * 不引用 Xposed API 类型，模块 App 进程与目标进程都能安全加载。
 */
data class ConfigSnapshot(
    val enabled: Boolean = ConfigKeys.DEFAULT_ENABLED,
    val scheduleEnabled: Boolean = ConfigKeys.DEFAULT_SCHEDULE_ENABLED,
    val windowStart: String = ConfigKeys.DEFAULT_WINDOW_START,
    val windowEnd: String = ConfigKeys.DEFAULT_WINDOW_END,
    val linkDnd: Boolean = ConfigKeys.DEFAULT_LINK_DND,
    val manualOverride: Int = ConfigKeys.OVERRIDE_AUTO,
    val logLevel: Int = ConfigKeys.DEFAULT_LOG_LEVEL,
    /** 跟随系统自动学习的入睡/起床时间；此时 [windowStart]/[windowEnd] 仅作手动备用值。 */
    val followSystem: Boolean = ConfigKeys.DEFAULT_FOLLOW_SYSTEM,
    /**
     * 设置页发来的"立即刷新系统作息"请求序号，0 表示没有请求。
     *
     * 放进快照是为了让 hook 侧的配置变更回调能顺带收到它 —— 快照变了才会回调。
     */
    val sleepRequest: Int = 0,
) {
    companion object {
        val DEFAULT = ConfigSnapshot()
    }
}
