plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.ztc1997.nocturne"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ztc1997.nocturne"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 完整 R8（full mode）：对整个程序统一优化，体积收益最大。
            //
            // 不要用实验性 packageScope 只优化 androidx/kotlin/kotlinx——
            // 那会把 kotlin.collections 内部类改成包私有，而跨包继承它的
            // 业务/Compose 类不在优化范围内、可见性没被同步放宽，运行时抛
            // IllegalAccessError（实测崩在 ModulePreferences.notifyChanged）。
            // 完整模式统一分析所有类，不存在跨优化边界问题。
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // META-INF/xposed/ 下的 module.prop / scope.list / java_init.list 必须原样进包。
    // 不裁剪这些元数据资源，否则 LSPosed 识别不到这是一个现代 API 模块。
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // material 提供 Theme.Material3.*（AndroidManifest 的 android:theme 用到）
    implementation(libs.material)

    // Compose + Material 3（BOM 统一版本，material3 等不写版本号）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    // -------------------------------------------------------------------
    // 现代 Xposed API（libxposed API 102）
    //
    // api 与 annotation 必须 compileOnly：运行时由框架在目标进程注入这些类。
    // 一旦被打进 APK，LSPosed 会拒绝加载并报
    //   "The Xposed API classes are compiled into the module's APK"
    // service 必须 implementation：它跑在模块自己的进程里，负责与 LSPosed
    // 服务通信（remote preferences 读写、作用域、热重载），会自动合并一个
    // XposedProvider（authority = <applicationId>.XposedService）。
    // -------------------------------------------------------------------
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    implementation(libs.libxposed.service)

    testImplementation(libs.junit)
}
