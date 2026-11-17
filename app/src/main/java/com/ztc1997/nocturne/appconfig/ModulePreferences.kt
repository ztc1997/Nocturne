package com.ztc1997.nocturne.appconfig

import android.content.Context
import android.content.SharedPreferences
import com.ztc1997.nocturne.core.ConfigKeys

/**
 * 模块 App 侧配置仓库。两个后端：
 *  - REMOTE：XposedService.getRemotePreferences(group)，框架托管，写进去
 *    目标进程立刻能看到并收到推送。正常状态的唯一真实来源。
 *  - LOCAL：模块自己的 SharedPreferences，仅在框架未连接（还没在 LSPosed
 *    里勾选）时兜底；框架一连接，本地值一次性迁移到远端。
 *
 * 本类不引用任何 io.github.libxposed.api 类型（只引用 service，后者会打包），
 * 可安全在模块 App 进程加载。
 */
object ModulePreferences {

    enum class Backend { REMOTE, LOCAL, NONE }

    private var local: SharedPreferences? = null
    @Volatile private var remote: SharedPreferences? = null
    @Volatile private var initialized = false
    private val listeners = mutableListOf<() -> Unit>()

    private const val MIGRATED_KEY = "__nocturne_migrated"

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        notifyChanged()
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val ctx = context.applicationContext
        local = ctx.getSharedPreferences(ConfigKeys.GROUP, Context.MODE_PRIVATE)
        FrameworkBridge.addListener { svc ->
            val rp = svc?.let { runCatching { it.getRemotePreferences(ConfigKeys.GROUP) }.getOrNull() }
            if (rp !== remote) {
                remote?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(changeListener) } }
                rp?.registerOnSharedPreferenceChangeListener(changeListener)
                remote = rp
            }
            if (rp != null) migrateLocalToRemote(rp)
            notifyChanged()
        }
        initialized = true
    }

    /** 首次连接时把本地暂存的配置补写进远端（远端已有的键保持权威，不覆盖）。 */
    private fun migrateLocalToRemote(rp: SharedPreferences) {
        val lp = local ?: return
        val localAll = lp.all
        if (localAll.isEmpty()) return
        if ((localAll[MIGRATED_KEY] as? Boolean) == true) return
        val remoteAll = runCatching { rp.all }.getOrNull() ?: return
        val missing = localAll.filterKeys { it in ConfigKeys.ALL_KEYS && it !in remoteAll }
        if (missing.isEmpty()) {
            lp.edit().putBoolean(MIGRATED_KEY, true).apply()
            return
        }
        runCatching {
            val e = rp.edit()
            for ((k, v) in missing) {
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is String -> e.putString(k, v)
                }
            }
            e.commit()
            lp.edit().putBoolean(MIGRATED_KEY, true).commit()
        }
    }

    private fun active(): SharedPreferences? = remote ?: local

    fun backend(): Backend = when {
        remote != null -> Backend.REMOTE
        local != null -> Backend.LOCAL
        else -> Backend.NONE
    }

    fun getBoolean(key: String, def: Boolean): Boolean =
        runCatching { active()?.getBoolean(key, def) ?: def }.getOrDefault(def)

    fun getInt(key: String, def: Int): Int =
        runCatching { active()?.getInt(key, def) ?: def }.getOrDefault(def)

    fun getString(key: String, def: String): String =
        runCatching { active()?.getString(key, def) ?: def }.getOrDefault(def)

    /** 写入配置（远端优先），返回是否成功。 */
    fun edit(block: SharedPreferences.Editor.() -> Unit): Boolean {
        val target = active() ?: return false
        return runCatching {
            val e = target.edit()
            e.block()
            e.commit()
        }.getOrDefault(false)
    }

    fun observe(l: () -> Unit) { listeners.add(l) }
    fun unobserve(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        for (l in listeners.toList()) {
            runCatching { l() }
        }
    }
}
