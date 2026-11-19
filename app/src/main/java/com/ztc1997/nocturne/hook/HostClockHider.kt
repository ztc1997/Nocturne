package com.ztc1997.nocturne.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.ztc1997.nocturne.core.ConfigSnapshot
import com.ztc1997.nocturne.core.GLog
import com.ztc1997.nocturne.core.SleepWindow
import io.github.libxposed.api.XposedInterface

/**
 * 第三方主题下锁屏 / AOD 时钟的星月占位。
 *
 * 屏幕上那串真实时间到底由谁画（真机逐层取证，2026-11-19，结论推翻过三次）：
 *  - 不是 personality 插件的 digital / text / gallery 族：`tryUpdateText` 只改到
 *    一批 `isShown=false` 的离屏控件，`applyTextClockTimeAndExtra`、
 *    `refreshClockText` 在锁屏上一次都没触发；
 *  - 不是 `CustomOplusKeyguardStyleClock`（id keyguard_style_clock）：那个实例
 *    alpha 置 0 后画面毫无变化，`keyguard_style_clock_enabled` 实测也是 0；
 *  - 而是 **VariUIEngine 自绘**：uiautomator dump 里时钟就是普通
 *    `android.widget.FrameLayout`（1272×2800 屏上 y=304..501、x=328..945），
 *    挂在 `com.android.systemui:id/keyguard_plugin_container` 下，没有 text、
 *    没有 id。容器实例就是 `KeyguardEngineContainer`，内部是
 *    `com.zplus.engine.lk_view.TimeView`（0-9 位图逐位 drawBitmap）；
 *  - AOD 同一套模板体系，类换成 `com.oplus.egview.widget.TimeView`
 *    （`mTextLines` 两行「时 / 分」用 canvas.drawText）。
 *
 * **为什么只能拦绘制**：这套引擎自己遍历子元素直接 `view.draw(canvas)`，不经
 * `ViewGroup.dispatchDraw`，所以 `visibility=INVISIBLE` 和 `alpha=0` 全部无效
 * （各自真机实测一次，画面照旧）。唯一入口是绘制方法本身，见
 * [EngineClockPainter]：不 proceed，改在原数字的格子上画星月。
 *
 * 本类里另有两组「状态写入口」钩子（`CustomOplusKeyguardStyleClock#setAlpha`
 * 那套 / `AodBlackLayout#setVisibility`），是早期路线留下的：那两个类都没有
 * draw/onDraw 可挂（实测「整块隐藏已挂 0 个钩子」），只能拦状态写入。它们对
 * 主题时钟不生效，但保留成本低，用于兜住宿主样式时钟被显示出来的场合。
 * 状态写入口只在宿主主动设置时被调，静止画面不会再触发，所以 attach 后起一个
 * [RECHECK_MS] 的主线程自检，翻转时重放宿主最后一次的写法。
 */
class HostClockHider(
    private val xposed: XposedInterface,
    private val classLoader: ClassLoader,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {

    private fun shouldMask(): Boolean = SleepWindow.shouldMask(
        config(),
        System.currentTimeMillis(),
        if (config().linkDnd) DndState.isActive() else false,
    )

    /** 受控的锁屏样式时钟实例 → 宿主最后一次请求的 alpha。 */
    private val alphaTargets =
        java.util.concurrent.ConcurrentHashMap<View, Float>()

    /** 受控的 AOD 容器实例 → 宿主最后一次请求的 visibility。 */
    private val visibilityTargets =
        java.util.concurrent.ConcurrentHashMap<View, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false

    fun install(): Int {
        var hooked = 0
        for (name in TARGET_CLASS_NAMES) {
            runCatching {
                val cls = Reflect.loadClass(name, classLoader)
                if (cls == null) {
                    GLog.w("宿主时钟容器不存在：$name（classLoader=$classLoader）")
                    return@runCatching
                }
                hooked += when (name) {
                    KEYGUARD_CLASS -> hookKeyguardStyleClock(cls)
                    else -> hookAodContainer(cls)
                }
            }.onFailure { GLog.w("宿主时钟容器处理失败：$name", it) }
        }
        GLog.i("宿主时钟整块隐藏已挂 $hooked 个钩子")
        // UIEngine 锁屏时钟：走的不是文本也不是 View 属性，只能拦 draw。
        val engineLoader = runCatching {
            DndState.currentAppContext()?.createPackageContext(
                "com.oplus.uiengine",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )?.classLoader
        }.getOrNull()
        GLog.i("引擎 classLoader：${engineLoader?.javaClass?.name ?: "null（回退宿主 CL）"}")
        hooked += hookEngineClocks(engineLoader ?: classLoader)
        // 锁屏容器靠 id 定位，装完立刻试一次，之后交给自检节拍。
        ensureTicking()
        handler.post { runCatching { applyContainer() } }
        // Application 通常再过几百毫秒才 attach，稍后补一次引擎钩子。
        handler.postDelayed(
            { runCatching { ensureEngineHook(DndState.currentAppContext()) } },
            2_000L,
        )
        return hooked
    }

    // ---------------------------------------------------------------- 锁屏

    /**
     * 屏幕上真正显示时间/日期的容器。
     *
     * 真机取证（2026-11-19）：第三方主题的锁屏时钟既不是 personality 的文本控件，
     * 也不是 `CustomOplusKeyguardStyleClock`（那个实例 alpha 置 0 后画面毫无变化），
     * 而是 **VariUIEngine 用普通 FrameLayout + Canvas 自绘**的：uiautomator dump 里
     * 时钟就是 `android.widget.FrameLayout`（1272×2800 屏上 y=304..501、x=328..945），
     * 日期是紧邻的另一个 FrameLayout（y=555..618），两者都挂在
     * `com.android.systemui:id/keyguard_plugin_container` 下，没有 text、没有 id。
     *
     * 所以不再逐级找时钟控件，直接按资源 id 定位这个容器并整块变透明。id 用
     * `getIdentifier` 现场解析，避免依赖 R 常量；窗口根通过 `WindowManagerGlobal`
     * 反射枚举（SystemUI 没有 Activity，拿不到 decorView）。
     */
    private var pluginContainer: View? = null
    private var locateLogged = false

    private fun windowRoots(): List<View> {
        val byMethod = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val m = wmg.getDeclaredMethod("getRootViews")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (m.invoke(inst) as? List<View>).orEmpty()
        }.getOrDefault(emptyList())
        if (byMethod.isNotEmpty()) return byMethod
        // 兜底：直接读私有字段 mViews（不同版本方法名有差异）。
        return runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (f.get(inst) as? List<View>).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun locatePluginContainer(): View? {
        // 已经拿到有内容的实例就直接复用。
        pluginContainer?.let {
            val n = (it as? android.view.ViewGroup)?.childCount ?: 0
            if (it.isAttachedToWindow && n > 0) return it
        }
        var best: View? = null
        var bestCount = -1
        for (root in windowRoots()) {
            val id = runCatching {
                root.context.resources.getIdentifier(
                    "keyguard_plugin_container",
                    "id",
                    "com.android.systemui",
                )
            }.getOrDefault(0)
            if (id == 0) continue
            val v = runCatching { root.findViewById<View>(id) }.getOrNull() ?: continue
            val n = (v as? android.view.ViewGroup)?.childCount ?: 0
            if (!locateLogged) {
                GLog.i(
                    "候选容器 ${v.javaClass.name} childCount=$n" +
                        " shown=${v.isShown} attached=${v.isAttachedToWindow}",
                )
            }
            if (n > bestCount) {
                bestCount = n
                best = v
            }
        }
        locateLogged = true
        if (best != null && bestCount > 0) {
            pluginContainer = best
            GLog.i("采用锁屏容器：${best.javaClass.name} 子节点数=$bestCount")
        }
        return pluginContainer
    }

    private var containerLogged = false

    /**
     * UIEngine / EGView 锁屏与 AOD 时钟的绘制接管。
     *
     * 这两处的数字不是文本：
     *  - 锁屏 `com.zplus.engine.lk_view.TimeView#draw`：0-9 位图数组逐位
     *    `canvas.drawBitmap`；
     *  - AOD `com.oplus.egview.widget.TimeView#onDraw`：`mTextLines` 两行
     *    `canvas.drawText`。
     *
     * 而且这套引擎**不通过 ViewGroup.dispatchDraw 出图**，是自己遍历子元素直接
     * `view.draw(canvas)`，所以 `visibility=INVISIBLE` 和 `alpha=0` 全部无效
     * （真机实测：两个都设了，画面照旧）。唯一入口就是绘制方法本身：睡眠时
     * 不 proceed，改由 [EngineClockPainter] 在原数字的格子上画星月——与桌面/
     * 状态栏是同一份字形实现，观感一致。
     */
    private fun hookEngineClocks(engineLoader: ClassLoader): Int {
        var n = 0
        for (name in ENGINE_CLOCK_CLASSES) {
            val cls = Reflect.loadClass(name, engineLoader)
            if (cls == null) {
                GLog.w("引擎时钟类不存在：$name（loader=${engineLoader.javaClass.name}）")
                continue
            }
            val kind = EngineClockPainter.kindFor(name)
            var perClass = 0
            for (methodName in arrayOf("draw", "onDraw")) {
                for (m in Reflect.methodsNamed(cls, methodName)) {
                    if (m.parameterTypes.size != 1) continue
                    if (m.parameterTypes[0] != android.graphics.Canvas::class.java) continue
                    perClass += runCatching {
                        bag.add(
                            xposed.hook(m)
                                .setId("nocturne:engineclock:${cls.simpleName}:$methodName")
                                .intercept { chain ->
                                    if (!shouldMask()) return@intercept chain.proceed()
                                    val self = chain.thisObject as? View
                                    val canvas = chain.args.getOrNull(0) as? android.graphics.Canvas
                                    if (kind != null && self != null && canvas != null) {
                                        // 画不出来也保持空白：绝不回落成真实时间。
                                        EngineClockPainter.draw(kind, canvas, self)
                                    }
                                    null
                                },
                        )
                        GLog.i("挂钩成功：引擎时钟 ${cls.simpleName}#$methodName（星月接管）")
                        1
                    }.getOrElse { GLog.w("挂引擎时钟失败：${cls.name}#$methodName", it); 0 }
                }
            }
            if (perClass == 0) GLog.w("引擎时钟 ${cls.simpleName} 无可挂的绘制方法")
            n += perClass
        }
        return n
    }

    private var engineHooked = false
    private var engineDiagLogged = false

    /** `ActivityThread.getSystemContext()`：还没有 Application 时的兜底 Context。 */
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
        val m = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
        m.invoke(thread) as? Context
    }.getOrNull()

    /**
     * 补装引擎时钟钩子。
     *
     * `install()` 跑在宿主 Application 刚创建时，`currentAppContext()` 还是 null
     * （实测 `引擎 classLoader：null`），而引擎类在 VariUIEngine 自己的
     * ClassLoader 里，宿主 CL 找不到。所以由 [ticker] 反复尝试，直到能拿到
     * Context 并 `createPackageContext` 成功。
     */
    private fun ensureEngineHook(ctx: Context?) {
        if (engineHooked) return
        // 引擎时钟只在 SystemUI（锁屏 + AOD）里拦。桌面那套走 personality 插件，
        // 已经有星月占位；这里如果一并拦掉，桌面时钟会变成空白而不是星月。
        val proc = runCatching { android.app.Application.getProcessName() }.getOrNull()
        if (proc != null && !proc.contains("systemui")) {
            engineHooked = true
            if (GLog.isDebug) GLog.d("非 SystemUI 进程（$proc），跳过引擎时钟钩子")
            return
        }
        val real = ctx ?: systemContext()
        if (real == null) {
            if (!engineDiagLogged) {
                engineDiagLogged = true
                GLog.w("引擎钩子等待 Context（currentAppContext / getSystemContext 均为空）")
            }
            return
        }
        val loader = runCatching {
            real.createPackageContext(
                "com.oplus.uiengine",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            ).classLoader
        }.getOrNull()
        if (loader == null) {
            if (!engineDiagLogged) {
                engineDiagLogged = true
                GLog.w("UIEngine 上下文不可用，继续重试")
            }
            return
        }
        engineHooked = true
        GLog.i("UIEngine classLoader 就绪，挂引擎时钟钩子 ${hookEngineClocks(loader)} 个")
    }

    private fun applyContainer() {
        val c = locatePluginContainer() ?: return
        runCatching { ensureEngineHook(c.context) }
        if (!containerLogged && (c as? android.view.ViewGroup)?.childCount ?: 0 > 0) {
            containerLogged = true
            GLog.i("锁屏引擎容器就绪：${c.javaClass.simpleName} 子节点数=${(c as? android.view.ViewGroup)?.childCount}")
        }
    }


    private fun hookKeyguardStyleClock(cls: Class<*>) : Int {
        var n = 0
        for (name in arrayOf("setAlpha", "setInnerAlpha")) {
            for (m in Reflect.methodsNamed(cls, name)) {
                if (m.parameterTypes.size != 1) continue
                if (m.parameterTypes[0] != java.lang.Float.TYPE) continue
                n += runCatching {
                    bag.add(
                        xposed.hook(m)
                            .setId("nocturne:hostclock:keyguard:$name")
                            .intercept { chain ->
                                val self = chain.thisObject as? View
                                val wanted = chain.args[0] as? Float ?: 1f
                                if (self != null) {
                                    alphaTargets[self] = wanted
                                    ensureTicking()
                                }
                                if (shouldMask()) {
                                    chain.proceed(arrayOf<Any?>(0f))
                                } else {
                                    chain.proceed()
                                }
                            },
                    )
                    GLog.i("挂钩成功：锁屏样式时钟 ${cls.simpleName}#$name")
                    1
                }.getOrElse { GLog.w("挂锁屏时钟 $name 失败", it); 0 }
            }
        }
        // getInnerAlpha 直接返回 0：部分渲染路径只读这个值决定内层可见度。
        for (m in Reflect.methodsNamed(cls, "getInnerAlpha")) {
            if (m.returnType != java.lang.Float.TYPE) continue
            n += runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("nocturne:hostclock:keyguard:getInnerAlpha")
                        .intercept { chain ->
                            if (shouldMask()) 0f else chain.proceed()
                        },
                )
                GLog.i("挂钩成功：锁屏样式时钟 ${cls.simpleName}#getInnerAlpha")
                1
            }.getOrElse { GLog.w("挂 getInnerAlpha 失败", it); 0 }
        }
        // attach 时补一刀：宿主可能在 attach 之前就把 alpha 设好了，之后不再调。
        for (m in Reflect.methodsNamed(cls, "onAttachedToWindow")) {
            n += runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("nocturne:hostclock:keyguard:attach")
                        .intercept { chain ->
                            val r = chain.proceed()
                            val self = chain.thisObject as? View
                            if (self != null) {
                                alphaTargets.putIfAbsent(self, 1f)
                                applyAlpha(self)
                                ensureTicking()
                                runCatching { applyContainer() }
                            }
                            r
                        },
                )
                GLog.i("挂钩成功：锁屏样式时钟 ${cls.simpleName}#onAttachedToWindow")
                1
            }.getOrElse { GLog.w("挂 onAttachedToWindow 失败", it); 0 }
        }
        return n
    }

    private fun applyAlpha(v: View) {
        val want = if (shouldMask()) 0f else (alphaTargets[v] ?: 1f)
        if (v.alpha != want) {
            runCatching { v.alpha = want }
            if (GLog.isDebug) GLog.d("锁屏样式时钟 alpha → $want")
        }
    }

    // ----------------------------------------------------------------- AOD

    private fun hookAodContainer(cls: Class<*>): Int {
        var n = 0
        for (m in Reflect.methodsNamed(cls, "setVisibility")) {
            if (m.parameterTypes.size != 1) continue
            if (m.parameterTypes[0] != Integer.TYPE) continue
            n += runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("nocturne:hostclock:aod:setVisibility")
                        .intercept { chain ->
                            val self = chain.thisObject as? View
                            val wanted = chain.args[0] as? Int ?: View.VISIBLE
                            if (self != null) {
                                visibilityTargets[self] = wanted
                                ensureTicking()
                            }
                            if (shouldMask()) {
                                chain.proceed(arrayOf<Any?>(View.INVISIBLE))
                            } else {
                                chain.proceed()
                            }
                        },
                )
                GLog.i("挂钩成功：AOD 容器 ${cls.simpleName}#setVisibility")
                1
            }.getOrElse { GLog.w("挂 AOD setVisibility 失败", it); 0 }
        }
        return n
    }

    private fun applyVisibility(v: View) {
        val want = if (shouldMask()) View.INVISIBLE else (visibilityTargets[v] ?: View.VISIBLE)
        if (v.visibility != want) {
            runCatching { v.visibility = want }
            if (GLog.isDebug) GLog.d("AOD 容器 visibility → $want")
        }
    }

    // ------------------------------------------------------------- 状态自检

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(ticker, RECHECK_MS)
    }

    private val ticker = object : Runnable {
        override fun run() {
            // 引擎钩子不依赖锁屏容器：AOD 场景下 keyguard_plugin_container 根本
            // 不存在，如果只在 locate 成功时补装，AOD 就会整段漏拦（真机踩过）。
            runCatching { ensureEngineHook(DndState.currentAppContext()) }
            applyContainer()
            for (v in alphaTargets.keys) applyAlpha(v)
            for (v in visibilityTargets.keys) applyVisibility(v)
            handler.postDelayed(this, RECHECK_MS)
        }
    }

    companion object {
        /** 隐藏期间的重检间隔：越短恢复越快，越长越省电。 */
        private const val RECHECK_MS = 20_000L

        private const val KEYGUARD_CLASS =
            "com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock"

        /**
         * 需要整块隐藏的容器（宿主类，走宿主 classLoader，能稳定命中）：
         *  - CustomOplusKeyguardStyleClock：锁屏样式时钟根视图（id keyguard_style_clock）
         *  - AodBlackLayout：主题息屏（workshop）黑底容器（id workshop_aod_black_container）
         */
        private val TARGET_CLASS_NAMES = arrayOf(
            KEYGUARD_CLASS,
            "com.oplus.systemui.aod.aodclock.off.AodBlackLayout",
        )

        /**
         * UIEngine / EGView 的时间控件。
         *
         * 只留**确实有绘制方法**的两个类（真机日志佐证：其余四个类
         * `AnalogClockElementView` / `TextClockElementView` / `TimeTextView` /
         * `AodDisplayTextView` 都没有 draw/onDraw(Canvas)，挂不上，纯噪音已删）。
         *
         * **不含日期类**（用户 2026-11-19 明确要求）：`DateTimeView`、`DateView`、
         * `LunarView` 一律放行，锁屏/AOD 上的「11月19日 星期四」保持原样。
         */
        private val ENGINE_CLOCK_CLASSES = arrayOf(
            EngineClockPainter.ZPLUS_CLASS,   // 锁屏：0-9 位图
            EngineClockPainter.EG_CLASS,      // AOD：两行 drawText
        )
    }
}
