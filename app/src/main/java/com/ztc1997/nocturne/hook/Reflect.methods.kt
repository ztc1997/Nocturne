package com.ztc1997.nocturne.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 轻量反射工具，仅依赖 JDK，模块 App 与目标进程都能用。 */
object Reflect {

    /** 用宿主 classLoader 加载类，失败返回 null。 */
    fun loadClass(name: String, cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    /**
     * 取某个类上所有可挂的同名方法。
     * 现代 API 的 hook(Executable) 需要具体的 Method，没有 hookAllMethods 批量写法，
     * 所以自己枚举。抽象方法过滤掉（LSPlant 会拒绝）。
     */
    fun methodsNamed(cls: Class<*>, name: String): List<Method> =
        cls.declaredMethods
            .filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
            .onEach { runCatching { it.isAccessible = true } }

    /** 沿继承链查找字段并设为可访问。 */
    fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            val found = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (found != null) {
                runCatching { found.isAccessible = true }
                return found
            }
            c = c.superclass
        }
        return null
    }
}
