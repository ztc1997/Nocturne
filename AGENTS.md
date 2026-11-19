# AGENTS.md — 夜曲 Nocturne

给 AI 助手 / 新协作者的项目速览。改代码前先读完这一页。

## 项目是什么

ColorOS 17（Android 17，一加 PLQ110）睡眠时段把系统各处时钟替换为占位符 `-` 的
LSPosed 模块。包名 `com.ztc1997.nocturne`，libxposed API 102，Kotlin。

## 目录结构

```
app/src/main/java/com/ztc1997/nocturne/
  core/      纯逻辑层（不依赖 Xposed API，双进程共用）
    ConfigKeys.kt       remote preferences 键表 + 默认值
    ConfigSnapshot.kt   配置不可变快照
    SleepWindow.kt      睡眠时段判定（含跨午夜）
    GLog.kt             统一日志出口（Sink 由 hook 入口注入）
  hook/      hook 层
    NocturneModule.kt           入口（META-INF/xposed/java_init.list 注册）
    ClockMaskFeature.kt         状态栏/锁屏(shared.clocks)/AOD 的替换分派
    ClockTargets.kt             hook 候选表（HookMode: SET_TEXT/RETURN_VALUE/HIDE_VIEWS）
    PersonalityClocksFeature.kt 插件时钟源头拦截（核心，见下）
    ConfigSource.kt             远端偏好配置通道
    DndState.kt / HookBag.kt / Reflect.methods.kt
  appconfig/ 模块 App 侧 UI 与配置写入
reverse/     真机逆向归档（见下节）
HOOK_PLAN.md hook 方案推导全文
```

## 构建 / 部署 / 调试（真机 USB）

```bash
./gradlew :app:assembleDebug
MSYS_NO_PATHCONV=1 adb install -r app/build/outputs/apk/debug/app-debug.apk
# 安装会自动重启作用域内进程；保险起见手动强杀：
PIDS=$(adb shell pidof com.android.launcher com.android.systemui | tr -d '\r')
adb shell "su -c 'kill -9 $PIDS'"
```

- 作用域：`com.android.systemui` + `com.android.launcher`（模块 App 内可改）
- **模块日志在 LSPosed 日志里，logcat 看不到**：
  `adb shell "su -c 'grep nocturne /data/adb/lspd/log/modules_*.log'"`
- 设备 root：KernelSU，shell 有 su 权限
- 诊断期 hook 进程日志强制 debug 级（`NocturneModule.install()` 里
  `GLog.level = 3`，配置变更回调同样强制）；问题收口后改回跟随配置

## 核心 hook 设计（真机验证结论）

ColorOS 17 时钟渲染分两套，都要拦：

1. **SystemUI 自带**（状态栏 + 部分 AOD）：
   - 状态栏 `com.android.systemui.statusbar.policy.Clock#getSmallTime`
     （RETURN_VALUE 模式，**必须先 proceed 再覆盖返回值**——内部懒初始化
     SimpleDateFormat，跳过 proceed 会 NPE 崩 SystemUI、触发 LSPosed 安全模式）
   - AOD `com.oplus.systemui.aod.aodclock.off.clock.DefaultClockView#updateTime` /
     `DialClockView#onDraw`（HIDE_VIEWS 模式，alpha 隐藏）
2. **插件 `KeyguardPersonalityClocks.apk`**（`/system_ext/app/`，桌面灵感时钟卡片
   + 锁屏个性时钟的实际渲染方）：
   - 宿主通过 `createPackageContext("com.oplus.keyguard.personality.clocks")`
     加载，拿到的 classLoader 与宿主实际持有的是**同一实例**（已验证，无分身问题）
   - 时间数字统一写入入口 `tryUpdateText(CharSequence)`，有 **3 层实现**，
     虚分派终点是 `digital.widget.MyCustomizedTextView`（覆写后直接 setText）：
     ```
     com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView  ← 必须挂
     com.oplus.keyguard.clock.common.widget.CustomizedTextView     ← 公共基类
     com.oplus.keyguard.clock.base.widget.CustomizedTextView      ← 中间层(final)
     ```
     只挂基类会被覆写完全绕过（教训：桌面短暂替换后恢复真实时间、锁屏不生效）
   - 拦截规则：写入内容为 1~2 位纯数字才替换为 `-`（冒号/AM/PM/日期/天气放行）；
     命中时 `chain.proceed(arrayOf("-"))` 保留原方法副作用
   - 安装时机：宿主 Application 创建后（Instrumentation.callApplicationOnCreate
     钩子）+ 状态栏钩子首次回调兜底；`onPackageReady` 时 Application 往往未 attach
3. **主题模板引擎自绘**（第三方主题的锁屏 + AOD，2026-11-19 真机取证）：
   - 锁屏 `keyguard_plugin_container` 的实例就是 `KeyguardEngineContainer`
     → VariUIEngine 的 `com.zplus.engine.lk_view.TimeView`：数字是 0-9 Bitmap，
     `draw(Canvas)` 里按 `getHourText()`/`getMinuteText()` 取位图逐位 `drawBitmap`
   - AOD 走 EGView 的 `com.oplus.egview.widget.TimeView`：垂直两行「时 / 分」，
     `onDraw` + `canvas.drawText`
   - **这套引擎自己遍历子元素直接调 `view.draw(canvas)`，不经
     `ViewGroup.dispatchDraw`，所以 `visibility=INVISIBLE` 和 `alpha=0` 一律无效**
     （真机两次实测：两个属性都设了，画面照旧）。唯一入口是绘制方法本身：
     睡眠时 `intercept` 返回 null，跳过整段绘制
   - 引擎类在 VariUIEngine（`com.oplus.uiengine`）自己的 ClassLoader 里，宿主 CL
     找不到；且 `install()` 执行时 `currentAppContext()` 仍为 null。所以由 20s
     自检节拍补装，`ActivityThread.getSystemContext()` 兜底
   - **只在 SystemUI 进程挂**：launcher 的时钟走 personality 的星月占位，
     一并拦会把桌面时钟变成空白
   - 日期类（`DateTimeView` / `DateView` / `LunarView`）放行——用户要的是
     「不显示时间」，日期与电量保持原样

### 血泪教训（勿重蹈）

- **切勿 hook `ClassLoader.loadClass`** 做"类加载看门狗"：在 loadClass 回调里再
  `xposed.hook()` 会触发框架类加载重入，真机实测直接把 LSPosed 打进安全模式
- 桌面时钟不要用"遍历 View 树写文本"的钩子（已删除 installDesktop）——与源头
  参数替换交替覆盖，会导致桌面在两种占位样式间跳动
- **`KeyguardEngineContainer` 整层 `alpha=0` 副作用极大**：它是整个主题锁屏
  壁纸层，连清晰壁纸底图和手电筒/相机快捷按钮一起抹掉，屏上只剩模糊壁纸
- **改完 APK 连杀几次 SystemUI 后，LSPosed 可能不再注入新进程**（日志停在被杀
  那一刻）。此时 `killall` 无效，必须 `adb reboot` 恢复——不要误判成代码问题
- RETURN_VALUE 模式永不跳过 `chain.proceed()`
- **模块 App 侧配置不要在组合期"读一次就记住"**：框架 binder 由 daemon 反向
  调用 `XposedProvider` 异步注入，release 包启动快，首帧常常赶在连接之前，
  那时只能读到本地文件（首次安装是空的）→ 设置页永远显示默认参数（2026-11-18
  修复）。现在 `ModulePreferences.load()` + 监听刷新，并且：
  - 写远端成功的键镜像一份到本地，冷启动先用它顶，不闪默认值
  - 框架没连上时的写入记进 `__nocturne_pending`，连接后覆盖式补写远端
    （否则那次保存会被永久丢在本地，远端旧值继续生效）
  - 界面有未保存改动（dirty）时不跟随后台推送，避免冲掉用户刚拨的开关

## reverse/ — 逆向归档

从真机系统 APK 用 jadx 1.5.6 反编译（2026-11-17），原始 APK 在 `D:/`：

| 目录 | 来源 APK（D:/） | 说明 |
|---|---|---|
| `out-personality/` | 锁屏相关 KeyguardPersonalityClocks | **最重要**：桌面/锁屏时钟全部实现 |
| `out-clockbase/` | clock.base | CustomizedTextView 基类族 |
| `out-aod/` | 息屏_17.001.0021.apk（com.oplus.aod） | AOD 设置页（渲染在 systemui） |
| `out-inspiration/` | 灵感组件_17.00.030.apk | 灵感组件（未 hook，时钟走 personality） |
| `out-systemui/` | 系统界面_17.99.02.apk | **仅节选**：shared/clocks、aod、statusbar/policy/Clock.java |
| `*.manifest.txt` / `*.log` | — | aapt manifest、jadx 日志 |

`out-launcher`（180M）与 systemui 其余部分未归档，原始工作目录
`C:/Users/ztc20/AppData/Local/Temp/rea_work/`（临时目录，可能已被清理）。
查锁屏时钟资源 id：`reverse/out-personality/resources/` 下搜
`visible_digital_time_text_view`。

## 约定

- 配置跨进程一律走 remote preferences（组 `nocturne_config`），键表只认
  `ConfigKeys`；hook 回调每帧读 `ConfigSource.snapshot`，不缓存
- core/ 层禁止 import 任何 `io.github.libxposed.api` 类型
- 新 hook 目标先进 `ClockTargets` 候选表再实现
- 占位符 = 星月图案（`core/Placeholder.kt`：每一位 ☾/✦（✦=U+2726 四芒星，
  2026-11-17 由五角星 ★ 改来），按分钟确定性随机
  `(分钟种子, 数字值) → splitmix64 → 1 bit`；**散列不含段内下标**——插件时钟
  是逐位写入（每位独立 TextView），状态栏是整串替换，含下标则两图案必然
  不同；去掉后跨面/跨 12-24 小时制天然一致，代价是重复数字整串同图案）。
  纯函数无状态，双进程自然一致。渲染分两路：**状态栏/下拉菜单（getSmallTime
  RETURN_VALUE）用纯字符占位，不挂 span**（同源文本，下拉菜单宿主会把 span
  重建为纯 String，索性两处观感一致）；插件时钟与锁屏 SET_TEXT 路径走
  `hook/StarMoonSpan.kt`
  （ReplacementSpan 自绘实芯四芒星 + 实芯月牙：高度按数字 "0" 的 cap band
  对齐、宽度逐位继承原数字 advance（spanned(masked, original)，original 是
  被替换文本），行度量保持主字体不变，解决 ☾/✦ 字体回退导致的桌面下移/
  锁屏上移与星月高低差）；span 被宿主链路重建丢失时退回字体回退渲染兜底。
  旧自定义横线占位符（`PLACEHOLDER` 配置键）已于 2026-11-17 删除，
  星月是唯一占位样式。各拦截面统一按"数字段"调 `Placeholder.mask()`：
  状态栏整串按非数字字符分段，插件时钟写入本身就是数字段，SET_TEXT 的
  时/分字段用系统当前时间构造 `%02d` 数字段
