package com.ztc1997.nocturne.hook

import android.os.Process
import com.ztc1997.nocturne.BuildConfig
import com.ztc1997.nocturne.core.ConfigSnapshot
import com.ztc1997.nocturne.core.GLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 夜曲 —— 现代 Xposed API（libxposed API 102）入口。
 *
 * 入口注册：META-INF/xposed/java_init.list 里的一行类名。
 *
 * 生命周期（框架按顺序回调）：
 *   onModuleLoaded   模块被载入某进程，一次
 *   onPackageReady   某包的 classLoader 就绪，在此挂钩子
 *   onHotReloading   旧代码收到热重载请求
 *   onHotReloaded    新代码就绪，自己重装钩子
 *
 * 注意：不要在构造函数里做任何初始化 —— 框架要先注入 framework 接口，
 * 那发生在 onModuleLoaded 之前、构造之后。
 */
class NocturneModule : XposedModule() {

    private var configSource: ConfigSource? = null
    private var hookBag: HookBag? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        GLog.install { priority, tag, msg, tr ->
            if (tr != null) log(priority, tag, msg, tr) else log(priority, tag, msg)
        }
        GLog.level = ConfigSnapshot.DEFAULT.logLevel

        val process = param.processName
        // system_server 里永远没有目标宿主，误勾作用域时在这里挡掉，
        // 避免给整个系统进程挂钩。
        if (param.isSystemServer) {
            GLog.i("system_server 不在作用域，detach")
            detach()
            return
        }

        GLog.i(
            "=== 夜曲 v${BuildConfig.VERSION_NAME} 载入 ===" +
                " process=$process pid=${Process.myPid()}" +
                " framework=${frameworkName} ${frameworkVersion}($frameworkVersionCode)" +
                " api=$apiVersion"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in SCOPE_PACKAGES) {
            GLog.d("跳过包 ${param.packageName}")
            return
        }
        install(param.classLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        GLog.i("收到热重载请求，允许")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        GLog.i("热重载完成，重装钩子（旧句柄 ${param.oldHookHandles.size} 个）")
        hookBag?.unhookAll()
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        hookBag = null
        configSource?.close()
        configSource = null

        val cl = currentAppClassLoader()
        if (cl == null) {
            GLog.e("热重载后拿不到宿主 classLoader，重启宿主即可恢复")
            return
        }
        install(cl)
    }

    private fun install(classLoader: ClassLoader) {
        val xposed: XposedInterface = this

        val configSource = ConfigSource(xposed)
        val bag = HookBag()

        // 插件时钟（personality.clocks）真实渲染桌面卡片与锁屏数字时钟。
        // 类不在宿主 classLoader 里，需要宿主 Context：走"延迟安装"路径
        // （Instrumentation.callApplicationOnCreate / 任一时钟钩子首次回调兜底）。
        // 注意：不要用 hook ClassLoader.loadClass 的"看门狗"方案 —— 在 loadClass
        // 回调里再挂钩会触发框架类加载重入，真机实测直接把 LSPosed 打进安全模式。
        val pluginFeature = PersonalityClocksFeature(xposed, { configSource.snapshot }, bag)
        val pluginOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val triggerPlugin: () -> Unit = {
            if (pluginOnce.compareAndSet(false, true)) {
                val ctx = DndState.currentAppContext()
                if (ctx == null) {
                    pluginOnce.set(false) // 下次回调再试
                } else {
                    pluginFeature.install(ctx)
                }
            }
        }

        val feature = ClockMaskFeature(xposed, classLoader, { configSource.snapshot }, bag, triggerPlugin)

        // 配置一变就更新日志等级；时钟判定每次回调都读最新快照，无需重装钩子。
        // 诊断期：hook 进程强制 debug 级（按调用日志是定位锁屏/AOD 问题的关键
        // 证据），问题解决后恢复为跟随配置。
        configSource.onChanged = { _ -> GLog.level = 3 }
        configSource.open()
        GLog.level = 3

        feature.install()
        installPluginClocks(xposed, configSource, bag, classLoader, pluginOnce)

        // 第三方主题：锁屏 / AOD 时钟由主题控件自绘（无文本可替换），
        // 只能整块跳绘。详见 HostClockHider。
        HostClockHider(xposed, classLoader, { configSource.snapshot }, bag).install()

        this.configSource = configSource
        this.hookBag = bag
    }

    /**
     * 安装 KeyguardPersonalityClocks 插件时钟拦截。
     *
     * onPackageReady 触发时宿主 Application 往往尚未 attach（实测 launcher 上
     * currentApplication() 为 null），而 createPackageContext 需要 Context，
     * 所以拿不到就先挂一个"Application 创建后"的触发钩子再补装。
     */
    private fun installPluginClocks(
        xposed: XposedInterface,
        configSource: ConfigSource,
        bag: HookBag,
        classLoader: ClassLoader,
        pluginOnce: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        val immediate = DndState.currentAppContext()
        if (immediate != null) {
            if (pluginOnce.compareAndSet(false, true)) {
                PersonalityClocksFeature(xposed, { configSource.snapshot }, bag).install(immediate)
            }
            return
        }
        GLog.i("宿主 Application 未 attach，插件时钟拦截改为延迟安装")
        val m = runCatching {
            Class.forName("android.app.Instrumentation", false, classLoader)
                .getDeclaredMethod("callApplicationOnCreate", android.app.Application::class.java)
        }.getOrNull() ?: run {
            GLog.w("找不到 Instrumentation.callApplicationOnCreate，插件时钟拦截跳过")
            null
        } ?: return
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        runCatching {
            bag.add(
                xposed.hook(m)
                    .setId("nocturne:personality:deferred")
                    .intercept { chain ->
                        val r = chain.proceed()
                        if (once.compareAndSet(false, true)) {
                            runCatching {
                                DndState.currentAppContext()?.let {
                                    PersonalityClocksFeature(xposed, { configSource.snapshot }, bag)
                                        .install(it)
                                    GLog.i("插件时钟拦截已在 Application 创建后完成")
                                }
                            }.onFailure { GLog.w("延迟安装插件时钟失败", it) }
                        }
                        r
                    },
            )
        }.onFailure { GLog.w("挂延迟安装钩子失败", it) }
    }

    private fun currentAppClassLoader(): ClassLoader? = runCatching {
        DndState.currentAppContext()?.classLoader
    }.getOrNull()

    companion object {
        /** 作用域：只有这些包里的时钟才替换。初期瞄准 ColorOS 17 的 SystemUI 与各家启动器。 */
        val SCOPE_PACKAGES = setOf(
            "com.android.systemui",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.oneplus.launcher",
            "com.android.launcher",
        )
    }
}
