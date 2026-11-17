package com.ztc1997.nocturne.core

/**
 * 配置键约定。模块 App 进程与目标进程共用这份常量表，
 * 不引用任何 io.github.libxposed.api 类型（App 进程里没有这些类）。
 */
object ConfigKeys {
    /** remote preferences 分组名，两侧必须一致 */
    const val GROUP = "nocturne_config"

    const val ENABLED = "enabled"
    const val SCHEDULE_ENABLED = "schedule_enabled"
    const val WINDOW_START = "window_start"
    const val WINDOW_END = "window_end"
    const val LINK_DND = "link_dnd"
    const val MANUAL_OVERRIDE = "manual_override"
    const val LOG_LEVEL = "log_level"

    // ---- 默认值 ----
    const val DEFAULT_ENABLED = true
    const val DEFAULT_SCHEDULE_ENABLED = true
    const val DEFAULT_WINDOW_START = "23:00"
    const val DEFAULT_WINDOW_END = "07:00"
    const val DEFAULT_LINK_DND = false
    const val DEFAULT_LOG_LEVEL = 2

    /** 手动覆盖：自动（按时段/勿扰判定） */
    const val OVERRIDE_AUTO = -1
    /** 手动覆盖：强制关闭（醒来要看时间） */
    const val OVERRIDE_OFF = 0
    /** 手动覆盖：强制开启（立刻屏蔽） */
    const val OVERRIDE_ON = 1

    val ALL_KEYS = setOf(
        ENABLED, SCHEDULE_ENABLED, WINDOW_START, WINDOW_END,
        LINK_DND, MANUAL_OVERRIDE, LOG_LEVEL,
    )
}
