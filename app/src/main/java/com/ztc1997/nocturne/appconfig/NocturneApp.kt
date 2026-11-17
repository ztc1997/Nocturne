package com.ztc1997.nocturne.appconfig

import android.app.Application
import com.ztc1997.nocturne.core.GLog

/** 模块 App 进程入口：接上框架服务与配置仓库。 */
class NocturneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FrameworkBridge.init()
        ModulePreferences.init(this)
        GLog.i("夜曲 App 启动，配置后端=${ModulePreferences.backend()}")
    }
}
