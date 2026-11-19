package com.ztc1997.nocturne.hook

import android.content.Context
import android.view.View
import android.widget.TextView
import com.ztc1997.nocturne.core.ConfigSnapshot
import com.ztc1997.nocturne.core.GLog
import com.ztc1997.nocturne.core.Placeholder
import com.ztc1997.nocturne.core.SleepWindow
import io.github.libxposed.api.XposedInterface
import java.util.Calendar

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
 *
 * 2026-11-19 第三方/全局主题适配：Launcher 进程在全局主题下
 * `ClockTimeView#refreshTimeText` 会把 `isImageDigitMode` 置 true，时间数字改由
 * `applyDigitDrawable` 贴主题图片数字，同时 `tryUpdateText` 只收到 null —— 上面的
 * 文本替换路径完全落空（真机现象：第三方主题桌面失效）。因此额外挂
 * `applyDigitDrawable`：命中睡眠时给写入目标 DigitalTimeView 设 alpha=0（隐藏图片
 * 数字），解除后在下一次刷新恢复 1f，与 AOD 的无文本隐藏策略一致。
 * 该分支只在 Launcher 生效（SystemUI 里 isImageDigitMode 恒为 false）。
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

    private fun shouldMask(): Boolean {
        val cfg = config()
        return SleepWindow.shouldMask(
            cfg,
            System.currentTimeMillis(),
            if (cfg.linkDnd) DndState.isActive() else false,
            SystemSleepSource.window(cfg.followSystem),
        )
    }

    /** 已经挂过 tryUpdateText 的 Class，避免同一 Class 被多路重复挂钩。 */
    private val hookedClasses = java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /** 诊断用：已经打印过视图信息的控件，避免每分钟刷屏。 */
    private val dumpedViews = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

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
                                if (PROBE_VIEW) {
                                    runCatching { dumpMaskedView(chain.thisObject, arg, masked) }
                                        .onFailure { GLog.w("[视图] 诊断失败", it) }
                                }
                                // 带 StarMoonSpan：星月自绘、逐位继承原数字 advance（见 StarMoonSpan）
                                val r = chain.proceed(
                                    arrayOf<Any?>(StarMoonSpan.spanned(masked, arg)),
                                )
                                if (PROBE_VIEW) {
                                    runCatching {
                                        val tv = chain.thisObject as? TextView
                                        GLog.d("[视图] 写入后 text=\"${tv?.text}\"")
                                    }.onFailure { GLog.w("[视图] 写入后读取失败", it) }
                                }
                                r
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
     * 诊断：把被改写的时间数字控件的位置、状态、最终文本、父链打出来。
     *
     * 目的：锁屏上明明执行了「屏蔽时间数字」，屏幕却仍显示真实时间。
     * 需要判断两种可能 —— 写法被后续覆盖，或者被改写的根本不是屏幕上那个控件
     * （例如动画用的离屏副本、AOD 副视图）。
     */
    private fun dumpMaskedView(self: Any?, arg: CharSequence?, masked: CharSequence) {
        val tv = self as? TextView
        if (tv == null) {
            GLog.d("[视图] 不是 TextView：self=${self?.javaClass?.name}")
            return
        }
        val hash = System.identityHashCode(tv)
        val first = dumpedViews.add(hash)
        val sb = StringBuilder("[视图] hash=$hash")
        sb.append(" class=${tv.javaClass.name}")
        sb.append(" id=")
        runCatching {
            sb.append("0x").append(Integer.toHexString(tv.id))
            if (tv.id != 0) sb.append("(").append(tv.resources.getResourceEntryName(tv.id)).append(")")
        }.onFailure { sb.append("?") }
        val rect = android.graphics.Rect()
        val vis = runCatching { tv.getGlobalVisibleRect(rect) }.getOrDefault(false)
        sb.append(" visible=$vis rect=$rect")
        sb.append(" w=${tv.width} h=${tv.height}")
        sb.append(" alpha=${tv.alpha} shown=${tv.isShown} attached=${tv.isAttachedToWindow}")
        sb.append(" text=\"${tv.text}\"")
        sb.append(" arg=\"$arg\" masked=\"$masked\"")
        if (first) {
            var p = tv.parent
            var d = 0
            while (p != null && d < 8) {
                if (p is View) {
                    sb.append("\n    <- ${p.javaClass.name}")
                    runCatching {
                        if (p.id != 0) {
                            sb.append("(").append(p.resources.getResourceEntryName(p.id)).append(")")
                        }
                    }
                }
                p = p.parent
                d++
            }
        }
        GLog.d(sb.toString())
    }

    /** 已经挂过 applyDigitDrawable 的 Class，避免同一 Class 被多路重复挂钩。 */
    private val hookedImageDigitClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /** 已经挂过 ThemeClockHelper.setTextImg 的 Class。 */
    private val hookedThemeImgClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 第三方/全局主题的「图片数字」写入源头（锁屏 + AOD）。
     *
     * 真机反编译：`com.oplus.keyguard.clock.base.ui.view.ClockTimeView` 在
     * `ThemeClockHelper.isClockImg()` 为 true 时，逐个数字位调用
     * `ThemeClockHelper.setTextImg(textView, "20", scale)`，而该方法实现是
     *
     *     textView.setText(null);
     *     textView.setCompoundDrawables(左位图, null, 右位图, null);
     *
     * —— 先把文本清空、再贴两张数字图片。所以我们挂在 `tryUpdateText` 上的星月
     * 文本会被紧接着清掉，屏幕上留下的是主题自带的真实数字图片。这就是
     * 「桌面已生效、锁屏/AOD 仍显示真实时间」的根因（桌面走 digital 族，锁屏与
     * AOD 走 base 族的图片数字路径）。
     *
     * 处理方式：命中睡眠时不执行原方法，改为把星月写进文本并清空四周 drawable，
     * 视觉上与桌面卡片的时间替换保持一致；未命中则原样放行，图片数字自然恢复。
     */
    private fun hookThemeClockImg(cls: Class<*>, source: String): Int {
        if (!hookedThemeImgClasses.add(cls)) return 0
        val methods = Reflect.methodsNamed(cls, "setTextImg")
        if (methods.isEmpty()) {
            GLog.w("setTextImg 不存在于 ${cls.name}（来源 $source）")
            return 0
        }
        var count = 0
        for (method in methods) {
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("nocturne:personality:themeimg:$source")
                        .intercept { chain ->
                            val tvForLog = runCatching { chain.getArg(0) as? TextView }.getOrNull()
                            if (GLog.isDebug) {
                                GLog.d(
                                    "setTextImg 回调 digits=\"${runCatching { chain.getArg(1) }.getOrNull()}\"" +
                                        " mask=${shouldMask()} view=${tvForLog?.javaClass?.name}",
                                )
                            }
                            if (!shouldMask()) {
                                chain.proceed()
                            } else {
                                val tv = runCatching { chain.getArg(0) as? TextView }.getOrNull()
                                val digits = runCatching {
                                    chain.getArg(1) as? String
                                }.getOrNull()
                                if (tv == null || digits.isNullOrEmpty()) {
                                    chain.proceed()
                                } else {
                                    val masked = Placeholder.mask(
                                        digits,
                                        Placeholder.minuteSeed(System.currentTimeMillis()),
                                    )
                                    if (GLog.isDebug) {
                                        GLog.d("屏蔽主题图片数字：$digits → \"$masked\"（$source）")
                                    }
                                    // 原方法会把文本清空并贴图，这里改成反向操作：
                                    // 清图 + 写入星月文本（StarMoonSpan 逐位继承原数字宽度）。
                                    tv.setCompoundDrawables(null, null, null, null)
                                    tv.text = StarMoonSpan.spanned(masked, digits)
                                    null
                                }
                            }
                        },
                )
                GLog.i("挂钩成功：主题图片数字 setTextImg（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩主题图片数字失败（来源 $source）", it) }
        }
        return count
    }
    /** 已经打印过「数字位仍是主题位图」告警的视图。 */
    private val warnedDigitViews =
        java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * 全局主题下贴图片数字的写入源头（`ClockTimeView#applyDigitDrawable`）。
     *
     * 2026-11-19 换机修正：上一版命中睡眠时把数字位 alpha 置 0，桌面时钟于是
     * **整块消失**（真机截图确认为空白），与状态栏/锁屏的星月完全不一致。现在
     * 数字位图已在取图源头（[hookClockNum]）换成 [StarMoonDrawable]，这里只做
     * 兜底与诊断：睡眠中若数字位上贴的仍不是星月（说明这条链路没走
     * getHostClockNum，例如别的主题走 ThemeClockHelper 缓存），就地再包一层，
     * 并打告警便于定位漏掉的取图入口。解除睡眠时什么都不做：宿主每次刷新都会
     * 重新 apply 原始位图，自然恢复。
     */
    private fun applyStarToDigitView(digitView: View?, source: String) {
        if (digitView == null || !shouldMask()) return
        val tv = runCatching {
            digitView.javaClass.getMethod("getVisibleTextView").invoke(digitView) as? TextView
        }.getOrNull() ?: return
        val digitDrawable = tv.compoundDrawables.getOrNull(0) ?: return
        // 星月已在取图源头换好；冒号是自己补的。都不该再被包一层。
        if (digitDrawable is StarMoonDrawable || digitDrawable is ColonDrawable) {
            if (GLog.isDebug && warnedDigitViews.add(System.identityHashCode(digitView))) {
                GLog.d("图片数字位已是星月/冒号（$source）")
            }
            return
        }
        if (warnedDigitViews.add(System.identityHashCode(digitView))) {
            GLog.w("图片数字位仍是主题位图，就地替换：${digitView.javaClass.name}（$source）")
        }
        val star = StarMoonGlyph.isStar(
            Placeholder.minuteSeed(System.currentTimeMillis()),
            0,
        )
        tv.setCompoundDrawables(StarMoonDrawable(digitDrawable, star), null, null, null)
    }

    /** 已经挂过 getHostClockNum 的 Class。 */
    private val hookedNumClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 主题数字位图的取图源头（全局主题的桌面/锁屏样式「图片数字」）。
     *
     * `Utils.getHostClockNum(context, digit)` 每次返回一张 0-9 数字位图，宿主随后
     * 把它缩放、贴成数字位的 compound drawable。这里换成 [StarMoonDrawable]：
     * intrinsic 尺寸沿用原位图，宿主算出的字宽/行高不变，只是画出来是星月；
     * 星/月由「分钟种子 + 数字值」决定，与状态栏/文本链路图案一致。
     *
     * 只在睡眠时段替换；平时原样返回，主题位图不受影响。
     */
    private fun hookClockNum(cls: Class<*>, source: String): Int {
        if (!hookedNumClasses.add(cls)) return 0
        var count = 0
        for (method in Reflect.methodsNamed(cls, "getHostClockNum")) {
            if (method.parameterTypes.size != 2) continue
            if (method.returnType != android.graphics.drawable.Drawable::class.java) continue
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("nocturne:personality:num:$source")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (!shouldMask()) return@intercept result
                            val digit = chain.getArg(1) as? Int ?: return@intercept result
                            val origin = result as? android.graphics.drawable.Drawable
                                ?: return@intercept result
                            if (origin is StarMoonDrawable) return@intercept origin
                            val star = StarMoonGlyph.isStar(
                                Placeholder.minuteSeed(System.currentTimeMillis()),
                                digit,
                            )
                            StarMoonDrawable(origin, star)
                        },
                )
                GLog.i("挂钩成功：主题数字取图 getHostClockNum（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩主题数字取图失败（$source）", it) }
        }
        if (count == 0) GLog.w("getHostClockNum 不可挂：${cls.name}（来源 $source）")
        return count
    }

    /** 已经挂过 getHostClockSymbol 的 Class。 */
    private val hookedSymbolClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /** 已经打过「补画冒号」日志的次数（避免每分钟刷屏）。 */
    private val colonLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 时间分隔符的取图源头（`Utils.getHostClockSymbol(context)`）。
     *
     * 真机实测：桌面那套模板在 launcher 侧取不到分隔符图，返回 null，
     * `applyDigitDrawable` 见到 null drawable 直接 return —— 数字换成星月之后
     * 冒号位置就是空的（用户 2026-11-19 反馈「桌面上的冒号没了」）。
     * 所以在源头兜底：主题没给就返回自己画的冒号（见 [ColonDrawable]），
     * 主题给了就原样用它的。
     */
    private fun hookClockSymbol(cls: Class<*>, source: String): Int {
        if (!hookedSymbolClasses.add(cls)) return 0
        var count = 0
        for (method in Reflect.methodsNamed(cls, "getHostClockSymbol")) {
            if (method.parameterTypes.size != 1) continue
            if (method.returnType != android.graphics.drawable.Drawable::class.java) continue
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("nocturne:personality:symbol:$source")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (!shouldMask()) return@intercept result
                            // 主题在 launcher 侧即使给得出分隔符图，画出来也是空的
                            // （真机 22:23 验证：hook 到的是非 null，屏幕上却没有冒号），
                            // 所以睡眠时段统一用自己补的冒号，与状态栏的「:」观感一致。
                            if (colonLogged.compareAndSet(false, true)) {
                                GLog.i(
                                    "补画冒号：原分隔符图=${if (result == null) "null" else "有（未采用）"}" +
                                        "（来源 $source）",
                                )
                            }
                            ColonDrawable()
                        },
                )
                GLog.i("挂钩成功：主题分隔符取图 getHostClockSymbol（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩主题分隔符取图失败（$source）", it) }
        }
        return count
    }

    /**
     * 全局主题下贴图片数字的写入源头。命中睡眠时把数字位换成星月（见
     * [applyStarToDigitView]/[hookClockNum]），不再 alpha 隐藏。
     */
    private fun hookImageDigit(cls: Class<*>, source: String): Int {
        if (!hookedImageDigitClasses.add(cls)) return 0
        val methods = Reflect.methodsNamed(cls, "applyDigitDrawable")
        if (methods.isEmpty()) {
            GLog.w("applyDigitDrawable 不存在于 ${cls.name}（来源 $source）")
            return 0
        }
        var count = 0
        for (method in methods) {
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("nocturne:personality:imgdigit:$source")
                        .intercept { chain ->
                            val result = chain.proceed()
                            // 第 0 个参数是 DigitalTimeView（FrameLayout，内含可见/不可见 TextView）。
                            applyStarToDigitView(
                                runCatching { chain.getArg(0) as? View }.getOrNull(),
                                source,
                            )
                            result
                        },
                )
                GLog.i("挂钩成功：图片数字 applyDigitDrawable（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩图片数字失败（来源 $source）", it) }
        }
        return count
    }

    /** 已经挂过 text 族 SetContent 的 Class。 */
    private val hookedTextContentClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /** 已经挂过 gallery 族 refreshClockText 的 Class。 */
    private val hookedGalleryClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 把时间 TextView 的内容改写成星月（按数字段，冒号保留）。
     * 原文用系统当前时间构造 "HH:mm"，供 StarMoonSpan 逐位继承宽度。
     * 已是星月（同分钟）时跳过，避免无谓重绘。
     */
    private fun maskTimeInto(tv: TextView) {
        val cal = Calendar.getInstance()
        val orig = String.format(
            "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
        val masked = Placeholder.mask(orig, Placeholder.minuteSeed(System.currentTimeMillis()))
        if (tv.text?.toString() != masked) tv.text = StarMoonSpan.spanned(masked, orig)
    }

    /**
     * text 族（锁屏 + AOD，`com.oplus.keyguard.clock.text.*`）。
     *
     * 该类家族不经过 `tryUpdateText`：`KeyguardSetContentHandler.applyTextClockTimeAndExtra`
     * 直接 `localTime.setText(str)` 写 "HH:mm"，是第三方主题锁屏显示真实时间的直接原因
     * （真机截图确认：锁屏 20:01 完整真实时间，日志里 digital 族的替换只作用于桌面卡片）。
     *
     * 采用 after 覆盖：原方法先写真实时间完成自身状态同步，命中睡眠再改写为星月；
     * 解除睡眠时不覆盖，真实时间自然保留。
     */
    private fun hookTextContent(cls: Class<*>, source: String): Int {
        if (!hookedTextContentClasses.add(cls)) return 0
        val methods = Reflect.methodsNamed(cls, "applyTextClockTimeAndExtra")
        if (methods.isEmpty()) {
            GLog.w("applyTextClockTimeAndExtra 不存在于 ${cls.name}（来源 $source）")
            return 0
        }
        var count = 0
        for (method in methods) {
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("nocturne:personality:textcontent:$source")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (shouldMask()) {
                                // arg0 = com.oplus.keyguard.clock.text.ui.view.ClockViewRoot
                                val root = runCatching { chain.getArg(0) }.getOrNull()
                                val tv = runCatching {
                                    root?.javaClass?.getMethod("getLocalTime")?.invoke(root) as? TextView
                                }.getOrNull()
                                if (tv != null) {
                                    runCatching { maskTimeInto(tv) }
                                        .onFailure { GLog.w("改写 text 族时间失败（$source）", it) }
                                    if (GLog.isDebug) GLog.d("屏蔽 text 族时间（$source）")
                                }
                            }
                            result
                        },
                )
                GLog.i("挂钩成功：text 族时间（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩 text 族失败（来源 $source）", it) }
        }
        return count
    }

    /**
     * gallery 族（灵感画廊时钟，`com.oplus.keyguard.clock.gallery.*`）。
     *
     * `BaseSingleClockView.refreshClockText` 同样直接 `timeTextView.setText(...)`，
     * 绕过 tryUpdateText。after 阶段取 `getTimeTextView()` 改写成星月。
     */
    private fun hookGalleryClock(cls: Class<*>, source: String): Int {
        if (!hookedGalleryClasses.add(cls)) return 0
        val methods = Reflect.methodsNamed(cls, "refreshClockText")
        if (methods.isEmpty()) {
            GLog.w("refreshClockText 不存在于 ${cls.name}（来源 $source）")
            return 0
        }
        var count = 0
        for (method in methods) {
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("nocturne:personality:gallery:$source")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (shouldMask()) {
                                val self = chain.thisObject
                                val tv = runCatching {
                                    self?.javaClass?.getMethod("getTimeTextView")?.invoke(self) as? TextView
                                }.getOrNull()
                                if (tv != null) {
                                    runCatching { maskTimeInto(tv) }
                                        .onFailure { GLog.w("改写 gallery 时间失败（$source）", it) }
                                    if (GLog.isDebug) GLog.d("屏蔽 gallery 族时间（$source）")
                                }
                            }
                            result
                        },
                )
                GLog.i("挂钩成功：gallery 族时间（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩 gallery 族失败（来源 $source）", it) }
        }
        return count
    }

    /** 已经挂过主题锁屏容器（StyleWidgetsContainer）的 Class。 */
    private val hookedStyleContainers =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 主题锁屏容器 `StyleWidgetsContainer`（personality 插件里的 FrameLayout 子类）。
     *
     * 2026-11-19 真机取证：uiautomator dump 显示屏幕上那串真实时间就是一个
     * `android.widget.FrameLayout`（1272×2800 屏上 y=304..501、x=328..945），日期是
     * 紧邻的另一个 FrameLayout（y=555..618），两者都挂在
     * `com.android.systemui:id/keyguard_plugin_container` 下；而 `tryUpdateText`
     * 改到的那批 `clock_time_*_txt` 全是 `shown=false` 的离屏控件。
     * 也就是说主题锁屏不写文本、直接按主题资源绘制，唯一可用的办法是把容器整块
     * 变透明。`onViewAdded` 是插件往容器里塞主题控件时的稳定回调，借它拿实例。
     */
    private fun hookStyleContainer(cls: Class<*>, source: String): Int {
        if (!hookedStyleContainers.add(cls)) return 0
        var count = 0
        for (name in arrayOf("onViewAdded", "onViewRemoved")) {
            for (method in Reflect.methodsNamed(cls, name)) {
                runCatching {
                    bag.add(
                        xposed.hook(method)
                            .setId("nocturne:stylecontainer:$name:$source")
                            .intercept { chain ->
                                val result = chain.proceed()
                                val self = chain.thisObject as? View
                                if (self != null) noteStyleContainer(self)
                                result
                            },
                    )
                    GLog.i("挂钩成功：主题锁屏容器 $name（来源 $source）")
                    count++
                }.onFailure { GLog.w("挂钩主题锁屏容器失败：$name（$source）", it) }
            }
        }
        return count
    }

    /** 诊断用：已经打印过结构的主题锁屏容器。 */
    private val notedContainers =
        java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private fun noteStyleContainer(v: View) {
        if (!notedContainers.add(System.identityHashCode(v))) return
        val rect = android.graphics.Rect()
        val vis = runCatching { v.getGlobalVisibleRect(rect) }.getOrDefault(false)
        val sb = StringBuilder(
            "[容器] ${v.javaClass.name} hash=${System.identityHashCode(v)}" +
                " rect=$rect visible=$vis shown=${v.isShown} alpha=${v.alpha}",
        )
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                val cr = android.graphics.Rect()
                val cv = runCatching { c.getGlobalVisibleRect(cr) }.getOrDefault(false)
                sb.append("\n    child[$i] ${c.javaClass.name} rect=$cr visible=$cv shown=${c.isShown}")
            }
        }
        var p = v.parent
        var d = 0
        while (p != null && d < 5) {
            if (p is View) sb.append("\n    <- ${p.javaClass.name} id=0x${Integer.toHexString(p.id)}")
            p = p.parent
            d++
        }
        GLog.i(sb.toString())
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
            val loader = runCatching {
                ctx.createPackageContext(
                    pkg,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                ).classLoader
            }.getOrNull()
            if (loader == null) {
                if (GLog.isDebug) GLog.d("插件不可用：$pkg")
                continue
            }
            // ① 文本数字写入源头（普通模式 / 灵感接续）。
            for (className in TARGET_CLASS_NAMES) {
                val tvCls = runCatching { loader.loadClass(className) }.getOrNull()
                if (tvCls == null) {
                    if (GLog.isDebug) GLog.d("类不存在：$pkg → $className")
                    continue
                }
                hookClass(tvCls, "fast-path:$pkg")
                if (hookedClasses.contains(tvCls)) hooked++
            }
            // ② 图片数字写入源头（全局/第三方主题，仅 Launcher 生效）。
            for (className in IMAGE_DIGIT_CLASS_NAMES) {
                val imgCls = runCatching { loader.loadClass(className) }.getOrNull()
                if (imgCls == null) {
                    if (GLog.isDebug) GLog.d("图片数字类不存在：$pkg → $className")
                    continue
                }
                hooked += hookImageDigit(imgCls, "fast-path:$pkg")
            }
            // ③ 图片数字的取图源头：换成星月 drawable（桌面不消失、与锁屏一致）。
            for (className in NUM_CLASS_NAMES) {
                val numCls = runCatching { loader.loadClass(className) }.getOrNull()
                if (numCls == null) {
                    if (GLog.isDebug) GLog.d("数字取图类不存在：$pkg → $className")
                    continue
                }
                hooked += hookClockNum(numCls, "fast-path:$pkg")
            }
            // ④ 分隔符取图源头：主题没给就补冒号。
            for (className in NUM_CLASS_NAMES) {
                val symCls = runCatching { loader.loadClass(className) }.getOrNull() ?: continue
                hooked += hookClockSymbol(symCls, "fast-path:$pkg")
            }
            // ③ text 族（第三方主题锁屏 / AOD）：直接 setText，绕过 tryUpdateText。
            for (className in TEXT_CONTENT_CLASS_NAMES) {
                val cls = runCatching { loader.loadClass(className) }.getOrNull()
                if (cls == null) {
                    if (GLog.isDebug) GLog.d("text 族类不存在：$pkg → $className")
                    continue
                }
                hooked += hookTextContent(cls, "fast-path:$pkg")
            }
            // ④ gallery 族（灵感画廊时钟）：同样直接 setText。
            for (className in GALLERY_CLOCK_CLASS_NAMES) {
                val cls = runCatching { loader.loadClass(className) }.getOrNull()
                if (cls == null) {
                    if (GLog.isDebug) GLog.d("gallery 族类不存在：$pkg → $className")
                    continue
                }
                hooked += hookGalleryClock(cls, "fast-path:$pkg")
            }
            // ⑤ 第三方/全局主题图片数字（锁屏 + AOD）：ThemeClockHelper.setTextImg。
            for (className in THEME_IMG_CLASS_NAMES) {
                val cls = runCatching { loader.loadClass(className) }.getOrNull()
                if (cls == null) {
                    if (GLog.isDebug) GLog.d("主题图片数字类不存在：$pkg → $className")
                    continue
                }
                hooked += hookThemeClockImg(cls, "fast-path:$pkg")
            }
            // ⑥ 主题锁屏容器：屏幕上真实时间/日期的宿主（实测不再是文本控件）。
            for (className in STYLE_CONTAINER_CLASS_NAMES) {
                val cls = runCatching { loader.loadClass(className) }.getOrNull()
                if (cls == null) {
                    if (GLog.isDebug) GLog.d("主题锁屏容器类不存在：$pkg → $className")
                    continue
                }
                hooked += hookStyleContainer(cls, "fast-path:$pkg")
            }
        }
        if (hooked > 0) GLog.i("插件时钟拦截已挂 $hooked 个钩子（快速路径）")
        return hooked
    }

    companion object {
        /** 诊断开关：打印被改写数字控件的身份/位置/父链（定位锁屏显示真实时间用）。 */
        const val PROBE_VIEW = false
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
         * 全局/第三方主题下贴图片数字的写入类（真机反编译：ClockTimeView.java）。
         * 只有 Launcher 进程的 refreshTimeText 会把 isImageDigitMode 置 true 并走
         * applyDigitDrawable；SystemUI 里该字段恒 false，仍走上面的文本路径。
         * 版本号后缀 `_release` 是 JADX 对内联合成方法名的标记，运行时方法名就是
         * applyDigitDrawable，类全名不含后缀。
         */
        val IMAGE_DIGIT_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.digital.ui.view.ClockTimeView",
        )

        /**
         * 图片数字的取图源头（真机反编译：digital/util/Utils.getHostClockNum）。
         * 全局主题下每个数字位都从这里取 0-9 位图，是唯一能把「位图」换成
         * 「星月」的地方；只包 applyDigitDrawable 只能拿到已贴好的 drawable，
         * 不知道这一位是几（星/月选择要按数字值散列，才能与状态栏一致）。
         */
        val NUM_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.digital.util.Utils",
        )

        /**
         * text 族时钟内容设置入口（锁屏 + AOD）。真机反编译确认
         * `applyTextClockTimeAndExtra` 里直接 `localTime.setText(str)`，
         * 不经过 tryUpdateText —— 第三方主题锁屏显示真实时间的直接原因。
         */
        val TEXT_CONTENT_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.text.ui.pipeline.handler.KeyguardSetContentHandler",
        )

        /**
         * gallery 族（灵感画廊）时钟。`BaseSingleClockView.refreshClockText`
         * 同样直接 `timeTextView.setText(...)`。
         */
        val GALLERY_CLOCK_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.gallery.view.BaseSingleClockView",
        )

        /**
         * 第三方/全局主题的「图片数字」写入工具类（锁屏 + AOD）。
         * `setTextImg` 会先 `setText(null)` 再贴数字位图，绕开一切文本钩子。
         */
        val THEME_IMG_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.base.util.ThemeClockHelper",
        )

        /**
         * 主题锁屏容器：主题自绘的锁屏时钟/日期的宿主 FrameLayout 子类。
         * uiautomator 实测屏幕上那串真实时间就是它（或它的子节点）画出来的。
         */
        val STYLE_CONTAINER_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.base.ui.view.StyleWidgetsContainer",
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
