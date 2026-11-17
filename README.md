# 夜曲 Nocturne

睡眠时段屏蔽系统时间显示。夜间醒来不看时间，避免"还有多久天亮"的焦虑计算，改善睡眠质量。

## 技术路线

基于 **libxposed 现代 API（API 102）** 的 LSPosed 模块，在 SystemUI / 启动器进程挂钩时钟控件的更新方法，命中屏蔽条件时把时间文本替换为占位符 `--:--`。

初期主要兼容 **ColorOS 17**（OPPO / 一加 / realme 同底座）。

### 触发策略（优先级从高到低）

1. 总开关关闭 → 不屏蔽
2. 手动覆盖：强制显示 / 强制屏蔽 → 直接决定
3. 与勿扰 / 睡眠模式联动开启且勿扰生效 → 屏蔽
4. 固定时段（支持跨午夜，如 23:00–07:00）→ 屏蔽
5. 其余 → 不屏蔽

配置项通过 `XposedService.getRemotePreferences()` 跨进程下发，改动即时生效，无需重启宿主进程。

## 工程结构

```
app/src/main/
├── resources/META-INF/xposed/     # 模块声明（API 102，非 legacy）
│   ├── module.prop                # minApiVersion=102 / exceptionMode=protective
│   ├── java_init.list             # 入口：NocturneModule
│   └── scope.list                 # 作用域候选（SystemUI / 各启动器）
├── java/com/ztc1997/nocturne/
│   ├── core/                      # 两进程共用，不引用 Xposed API
│   │   ├── ConfigKeys.kt          # 配置键与默认值
│   │   ├── ConfigSnapshot.kt      # 配置快照
│   │   ├── SleepWindow.kt         # 时段判定（纯逻辑，可单测）
│   │   └── GLog.kt                # 日志出口（函数式注入，不依赖 API）
│   ├── hook/                      # 仅目标进程，可引用 API
│   │   ├── NocturneModule.kt      # 模块入口与生命周期
│   │   ├── ConfigSource.kt        # 目标进程读取 remote preferences
│   │   ├── ClockMaskFeature.kt    # 时间屏蔽判定 + 占位符替换
│   │   ├── ClockTargets.kt        # 时钟 hook 点候选表（待真机校准）
│   │   ├── HookBag.kt             # 钩子句柄管理（现代 API 需持有句柄）
│   │   ├── DndState.kt            # 勿扰模式检测
│   │   └── Reflect.*.kt           # 反射工具
│   └── appconfig/                 # 模块 App 进程，不引用 API
│       ├── NocturneApp.kt         # Application 入口
│       ├── FrameworkBridge.kt     # 框架服务连接
│       ├── ModulePreferences.kt   # 配置写入（远端优先，本地兜底）
│       └── MainActivity.kt        # 设置页
└── res/values/strings.xml         # 模块名 = 夜曲
```

## 构建与安装

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

1. 手机需 Root + LSPosed（框架需支持 Xposed API 102）。
2. 安装 APK，在 LSPosed 中启用模块。
3. **勾选作用域**：`com.android.systemui`，以及设备上实际的启动器包
   （ColorOS 17 本机实测为 `com.android.launcher`，其它机型可能是
   `com.oppo.launcher` / `com.coloros.launcher` / `com.oneplus.launcher` 之一）。
   - 注意：`com.oplus.aod` 与 `com.oplus.keyguard.style.widgets` **不需要**勾选——
     AOD 时钟与锁屏时钟都在 SystemUI 进程内渲染。
4. 强制停止 SystemUI 与启动器（或重启手机）让 hook 生效。
5. 打开"夜曲"App 配置时段与联动开关。

> 未连接框架时，App 会把配置暂存在本地；首次在 LSPosed 勾选后自动迁移到远端。

## 当前状态与已知限制

已基于 ColorOS 17 真机 APK 的静态反编译（详见 [HOOK_PLAN.md](HOOK_PLAN.md)）落地以下 hook：

| 时钟 | 目标类 / 方法 | 方式 |
|---|---|---|
| 状态栏 | `Clock#getSmallTime()` | 返回值直接替换为占位符（避开 RedOne 异步 setText 覆盖） |
| 锁屏横排流体 | `RedHorizontalSingleClockView#refresh()` + `RedTextClock#onTimeChanged()` | after 写字段 |
| 锁屏单时钟 | `SingleClockView#refresh()` | after 写 `mTimeHour` / `mTimeMinute` |
| 锁屏双时钟 | `DualClockView$updateLocatedTime$1$1#run()` | after 写字段（写入在异步 runnable 内） |
| AOD 息屏数字 | `DefaultClockView#updateTime()` | 每次刷新按判定设横/纵容器 alpha（命中透明，解除恢复） |
| AOD 息屏表盘 | `DialClockView#onDraw()` | 每次刷新按判定设 View 自身 alpha（Canvas 自绘无文本） |
| 桌面 / 锁屏插件时钟 | `MyCustomizedTextView#tryUpdateText()`（插件 `KeyguardPersonalityClocks`） | 源头替换写入参数（1~2 位纯数字换星月） |

> AOD 时钟无 TextView 可替换：数字是 `setImageResource` 贴的图片、表盘是 `Drawable.draw` 自绘。
> 因此屏蔽时让时间区域透明（而非显示 `--:--`）；AOD 在 SystemUI 进程，无需额外作用域。
> 依赖 `AodClockLayout#performLayoutUpdate()` 的分钟级刷新自动恢复，解除屏蔽后 ≤1 分钟回到正常显示。

**尚未覆盖**（按用户决定暂缓）：

- 桌面外部卡片时钟（天气 / 世界 / 本地 / 城市）：由 `com.coloros.alarmclock` / `com.coloros.widget` / `com.coloros.weather2` 等独立 AppWidget Provider 渲染，需另取 APK 后分析（见 HOOK_PLAN.md §2.4.1）。
- AOD 的 RAM-less 新渲染样式（`shouldUseNewRenderMethod()` 为真时由独立 AOD 插件 APK 渲染）：本机 `com.oplus.aod` 仅是设置页，该样式需实机确认是否需要追加。

**需要在真机上校准的部分**：

- `param.processName` 的确切值；锁屏当前生效的是三种样式中的哪一套（日志会打印挂钩命中行）。

### 反编译校准步骤

1. 从设备拉取对应 APK（`adb shell pm path <包名>`），放到工作目录。
2. 用 jadx 反编译，搜索时钟类与 `setText` / `HH:mm` / `refresh`。
3. 确认类名/方法名后加入 `ClockTargets.ALL` 候选。
4. 用 LSPosed 日志（tag `Nocturne`）确认"挂钩成功"行，判断候选是否生效。

## 验证

```bash
./gradlew.bat testDebugUnitTest     # SleepWindow 纯逻辑单测
./gradlew.bat assembleDebug         # 编译 APK
```
