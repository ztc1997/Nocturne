package com.ztc1997.nocturne.hook

import android.content.Context
import android.view.View
import com.ztc1997.nocturne.core.ConfigSnapshot
import com.ztc1997.nocturne.core.GLog
import com.ztc1997.nocturne.core.Placeholder
import com.ztc1997.nocturne.core.SleepWindow
import io.github.libxposed.api.XposedInterface

/**
 * KeyguardPersonalityClocks 插件时钟拦截（真机反编译见 HOOK_PLAN.md §2.6）。
 *
 * 桌面卡片时钟与锁屏数字时钟的实际渲染方是插件
 * `com.oplus.keyguard.personality.clocks`（KeyguardPersonalityClocks.apk）：
 * DigitalTimeView / ClockTimeView 等把时间数字写入全部收敛到
 * `CustomizedTextView#tryUpdateText(CharSequence)`（正常路径与翻页动画路径都调它）。
 * 之前的"写控件文本"式 hook 会被时钟自身的分钟级刷新覆盖（竞态），因此必须在
 * 这个写入源头上把参数换掉，写入内容从一开始就是占位符。
 *
 * 插件类通过 createPackageContext 加载进宿主进程（launcher / systemui），
 * 所以要用插件的 classLoader 找类，而不是宿主的。
 *
 * 拦截条件刻意收窄：写入内容是 1~2 位纯数字（时间数字位）才替换为星月占位
 * （core/Placeholder.kt，按分钟确定性变化）。
 * 冒号（":"）、AM/PM、日期（"11月17日"）、天气（"30°"）都不是纯数字，自动放行。
 * 未命中睡眠时段时不做任何替换，原样 proceed。
 */
class PersonalityClocksFeature(
    private val xposed: XposedInterface,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {

    /** 写入内容是否为时间数字位：1~2 位纯数字（如 "1"、"14"、"08"）。 */
    private fun isTimeDigitArg(arg: CharSequence?): Boolean {
        if (arg == null) return false
        val n = arg.length
        if (n < 1 || n > 2) return false
        for (i in 0 until n) {
            if (!Character.isDigit(arg[i])) return false
        }
        return true
    }

    private fun shouldMask(): Boolean = SleepWindow.shouldMask(
        config(), System.currentTimeMillis(),
        if (config().linkDnd) DndState.isActive() else false,
    )

    /** 已经挂过 tryUpdateText 的 Class，避免同一 Class 被多路重复挂钩。 */
    private val hookedClasses = java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 在给定 Class 上挂 tryUpdateText 钩子（幂等：同一 Class 只挂一次）。
     * @param source 日志里标注的安装来源
     */
    private fun hookClass(cls: Class<*>, source: String) {
        if (!hookedClasses.add(cls)) return
        val method = runCatching {
            cls.getDeclaredMethod("tryUpdateText", CharSequence::class.java)
        }.getOrNull()
        if (method == null) {
            GLog.w("tryUpdateText 不存在于 ${cls.name}（来源 $source）")
            return
        }
        runCatching { method.isAccessible = true }
        runCatching {
            bag.add(
                xposed.hook(method)
                    .setId("nocturne:personality:$source")
                    .intercept { chain ->
                        if (!shouldMask()) {
                            chain.proceed()
                        } else {
                            val arg = runCatching { chain.getArg(0) as? CharSequence }.getOrNull()
                            if (isTimeDigitArg(arg)) {
                                // 写入内容本身就是一个数字段：逐位换成星月（确定性按分钟变化），
                                // 同分钟内结果恒定，去重与 RedOne 刷新等副作用照常保留。
                                val masked = Placeholder.mask(
                                    arg,
                                    Placeholder.minuteSeed(System.currentTimeMillis()),
                                )
                                if (GLog.isDebug) GLog.d("屏蔽时间数字：$arg → \"$masked\"（$source）")
                                // 带 StarMoonSpan：星月自绘、逐位继承原数字 advance（见 StarMoonSpan）
                                chain.proceed(arrayOf<Any?>(StarMoonSpan.spanned(masked, arg)))
                            } else {
                                // 非纯数字放行（冒号/AM/PM/日期/天气）；debug 下记录
                                // 数字类以外的写入，用于排查唤醒瞬间是否走了别的入口。
                                if (GLog.isDebug && arg != null && arg.length <= 24) {
                                    GLog.d("放行写入：\"$arg\"（$source）")
                                }
                                chain.proceed()
                            }
                        }
                    },
            )
            GLog.i("挂钩成功：插件时钟 tryUpdateText（来源 $source → ${cls.name}，classLoader=${cls.classLoader}）")
        }.onFailure { GLog.w("挂钩插件时钟失败（来源 $source）", it) }
    }

    /**
     * 快速路径：用宿主 Context 的 createPackageContext 找插件 classLoader 挂钩，
     * 拿到的 classLoader 与宿主实际持有的是同一实例。
     * @param appContext 宿主 Application Context（用于 createPackageContext）
     * @return 成功挂上的钩子数
     */
    fun install(appContext: Context?): Int {
        val ctx = appContext ?: run {
            GLog.w("宿主 Context 未就绪，插件时钟拦截延迟到下次触发")
            return 0
        }
        var hooked = 0
        for (pkg in PLUGIN_PACKAGES) {
            for (className in TARGET_CLASS_NAMES) {
                val tvCls = runCatching {
                    val pctx = ctx.createPackageContext(
                        pkg,
                        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                    )
                    pctx.classLoader.loadClass(className)
                }.getOrNull()
                if (tvCls == null) {
                    if (GLog.isDebug) GLog.d("插件不可用或类不存在：$pkg → $className")
                    continue
                }
                hookClass(tvCls, "fast-path:$pkg")
                if (hookedClasses.contains(tvCls)) hooked++
            }
        }
        if (hooked > 0) GLog.i("插件时钟拦截已挂 $hooked 个钩子（快速路径）")
        return hooked
    }

    companion object {
        /**
         * tryUpdateText 的全部实现（真机反编译确认）：
         * - common.widget.CustomizedTextView：公共基类（声明处）
         * - base.widget.CustomizedTextView：中间基类，**final**，只会被非覆写子类用到
         * - digital.widget.MyCustomizedTextView：时间数字控件的**虚分派终点**！
         *   覆写了 tryUpdateText 并直接 setText。只挂基类钩子时，所有时间数字
         *   写入走这个覆写、完全绕过基类钩子 —— 桌面恢复真实时间、锁屏不生效
         *   的根因。必须排在第一位。
         * digital.ui.view.DigitalTimeView#tryUpdateText 只是转发层（转发到
         * MyCustomizedTextView），挂终类即可覆盖。
         */
        val TARGET_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView",
            "com.oplus.keyguard.clock.common.widget.CustomizedTextView",
            "com.oplus.keyguard.clock.base.widget.CustomizedTextView",
        )

        /**
         * 插件宿主包。CustomizedTextView 实际位于 KeyguardPersonalityClocks.apk；
         * 同时尝试 clock.base（独立 APK），避免版本演进后类搬家。
         */
        val PLUGIN_PACKAGES = arrayOf(
            "com.oplus.keyguard.personality.clocks",
            "com.oplus.keyguard.clock.base",
        )
    }
}
