plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val purgeStaleRoomGeneratedSources by tasks.registering(Delete::class) {
    delete(
        layout.buildDirectory.dir("generated/ksp/debug/java/com/example/localmovielibrarz/data/local"),
        layout.buildDirectory.dir("kspCaches/debug/backups/java/byRounds")
    )
}

// 要打进 APK 的 ABI。优先级：
//   1) -PmpvAbis=arm64-v8a,...       显式指定，最高优先
//   2) jniLibs 里实际装了 .so 的目录   自动探测
//   3) 三个全 ABI                     jniLibs 空着时的兜底（让门禁把 27 条缺库全报出来）
//
// 为什么要有 (2)：交付的单文件 zip 里只带 arm64 一套库（三个 ABI 共 74 MB，超过
// 24 MB 的上传通道上限）。如果默认仍是三个 ABI，解开 zip 直接 ./gradlew 就会被
// 门禁以「armeabi-v7a: 目录不存在」拦下 —— 明明是刚下发的包，却必须先读文档再加
// 参数才能编。按目录探测之后，「包里有什么就编什么」变成默认行为。
//
// 注意：探测空目录时不能直接当成「没有 ABI」（那会产出不含任何 .so 的 APK），
// 所以空目录仍然回退到三个，让门禁失败而不是静默出坏包。
val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val detectedAbis: List<String> = file("src/main/jniLibs").listFiles()
    ?.filter { dir ->
        dir.isDirectory &&
            dir.listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true
    }
    ?.map { it.name }
    ?.filter { it in knownAbis }
    ?.sorted()
    .orEmpty()

val mpvAbis: List<String> = (findProperty("mpvAbis") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: detectedAbis.ifEmpty { knownAbis }

android {
    namespace = "com.example.localmovielibrarz"
    compileSdk = 35

    // libmpv JNI 封装层（app/src/main/cpp）用 NDK 编译成 libplayer.so。
    // 显式 pin NDK 版本：不写的话 AGP 会取自身默认版本，CI 与本机可能不一致。
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.example.localmovielibrarz"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "3.2"

        // mpv 原生链路由 app/src/androidTest/.../MpvPlaybackSmokeTest.kt 做运行期验证
        // （打包层 / 加载层 / 解码层 / 渲染层四级）。测试 APK 只在 ./gradlew
        // connectedAndroidTest 时构建，不进 release 产物。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── ABI 白名单（2026-10-28 libmpv 修复）──────────────────────────────
        //
        // 之前这里没有 abiFilters，AGP 默认给 4 个 ABI（arm64-v8a / armeabi-v7a /
        // x86 / x86_64）各跑一遍 CMake；而 jniLibs 里一个 .so 都没有，于是 4 个
        // ABI 全部编出 stub 版 libplayer.so（MPV_AVAILABLE=0）。APK 能装能跑，
        // 但 mpv 引擎恒判定不可用 —— 用户看到的就是
        // 「未检测到 libmpv.so：原生库缺失」。
        //
        // 现在 jniLibs 已按 ABI 装上预编译 libmpv.so 及其兄弟库（装配脚本：
        // scripts/assemble-libmpv-jnilibs.py）。显式钉住这三个 ABI，是为了让
        // 「jniLibs 里有哪几个 ABI」和「APK 里编哪几个 ABI」变成同一件事：
        //   - x86 不再进包：预编译 libmpv 不提供 x86，32 位 x86 设备上 mpv
        //     本来就不可用（仍可回退 ExoPlayer），白编一份 stub 没有意义；
        //   - 每多一个 ABI，就多一种「有 libplayer.so 却没有 libmpv.so」的
        //     半装组合，白名单把这种组合的可能数量压到零。
        //
        // 文件末尾的 verifyMpvNativeLibs 用同一份白名单反向校验 jniLibs，
        // 两边一旦不一致，构建直接失败，而不是运行期静默降级。
        ndk {
            abiFilters += mpvAbis
        }

        // CMakeLists 只编 JNI 封装层；libmpv.so 本身是外部预编译产物，
        // 由 scripts/assemble-libmpv-jnilibs.py（或 CI artifact）放进 jniLibs/<abi>/。
        // 缺库时 CMake 退化为 stub 版 libplayer.so，assemble 本身不会失败——
        // 真正的门禁是 verifyMpvNativeLibs。
        externalNativeBuild {
            cmake {
                // 用静态 STL：封装层是纯 JNI C 风格代码（CMakeLists 里已 -fno-exceptions
                // -fno-rtti），静态链接可以少打包一个 libc++_shared.so，也不会和
                // libmpv.so 自带的 C++ 运行时在同一个进程里撞符号。
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    // 16 KB 页对齐（Android 15 / API 35 起 64 位设备强制要求）。
                    // NDK r27 默认仍按 4 KB 对齐；r28+ 才默认 ON。本项目 pin 的是
                    // r27，必须显式打开，否则 libplayer.so 会在 Play Console 与
                    // 「应用兼容性」检查里被标为未对齐。等价于给链接器加
                    // -Wl,-z,max-page-size=16384，CMakeLists 里也兜了一层。
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    // 关掉 CMake 的「构建前自动重跑 configure」规则。
                    //
                    // 不加这个，ninja 会在每次构建时重跑 cmake 重新生成 build.ninja，
                    // 跑完再 stat 一次发现 build.ninja 仍然「新不过」它的输入，于是
                    // 重来——循环 100 次后报：
                    //   ninja: error: manifest 'build.ninja' still dirty after 100 tries
                    // 症状就是 CMakeLists 里的 message() 被反复刷屏（每个 ABI 每轮一次，
                    // 4 个 ABI × 100 轮），看起来像 mpv 配置错了，其实只是 configure 在跑圈。
                    //
                    // 触发条件通常是「某个 configure 输入文件的 mtime 不比自己写出的
                    // build.ninja 旧」——CI 从缓存恢复 .cxx / build 目录时最典型
                    // （actions/cache 按 tar 还原，可能带回旧 mtime + 时钟偏差）。
                    // 与其逐个排查哪个输入 dirty，不如直接不生成这条规则：
                    // build.ninja 只在 .cxx 为空时由 AGP 生成一次，此后不再自检。
                    //
                    // 代价很小：AGP 自己有一层任务级增量判断（configureCMakeDebug[abi]
                    // 把 CMakeLists.txt、*.cmake、ndk 工具链文件都声明成了输入），
                    // 所以改 native 代码仍然会正常重新 configure。这里去掉的只是
                    // ninja 自己那条多余的自检——两套机制检查同一件事，后者出错时
                    // 会直接把构建带死。
                    "-DCMAKE_SUPPRESS_REGENERATION=ON",
                )
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    // 16 KB 页对齐的第 2 层：APK 里 .so 的存储方式。
    //
    // 16 KB 要求是两件事，缺一不可：
    //   1) ELF 段对齐  —— 编译期，每个 PT_LOAD 的 p_align >= 0x4000
    //                     （NDK r28+ 默认；r27 靠 ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON）
    //   2) zip 偏移对齐 —— 打包期，未压缩的 .so 在 APK 内的起始偏移能被 16384 整除
    //
    // useLegacyPackaging 控制第 2 层走哪条路：
    //   true  → .so 被压缩存进 APK，安装时解压到磁盘。运行没问题，但 Play Console
    //           的 16 KB 检查看的是 APK 内偏移，压缩条目一律判不合格。
    //   false → .so 不压缩存储，AGP 8.5.1+ 会用 -P 16 做偏移对齐，两层都满足。
    //
    // minSdk = 26 时 AGP 默认已是 false，这里显式写死是为了防止后续有人调 minSdk
    // 或加 useLegacyPackaging 时无声退化——一旦退化，构建期不会报任何错，
    // 只有上传 Play Console 才会被打回。
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // 纯 JVM 单元测试要能碰 `android.util.Log`：`MpvHttpProxy` 里每次请求都会打日志，
    // 不开这个开关，`MpvHttpProxyTest` 一跑就 `RuntimeException: Method i in
    // android.util.Log not mocked`。改成返回默认值后 Log.* 变成空操作，测试只验行为。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 2.3 已移除 kotlinOptions.jvmTarget，改用 compilerOptions DSL。
    // 用全限定名避免 JvmTarget 在 .kts 顶级作用域未解析的问题。
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// 导出 Room schema（5.6 优化）：追踪数据库 schema 变更历史，便于生成迁移测试
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.matching { task ->
    task.name in setOf("kspDebugKotlin", "compileDebugKotlin")
}.configureEach {
    dependsOn(purgeStaleRoomGeneratedSources)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00") // 跟随 Kotlin 2.3 的 Compose 编译器版本
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06") // 光鸭令牌加密存储 (P1-2)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // 16 KB 页对齐：显式钉死 graphics-path 到首个 16 KB 对齐的版本。
    //
    // 这个 AAR 里带 libandroidx.graphics.path.so，是 APK 中除 libplayer.so 之外
    // 唯一的原生库。实测（dl.google.com 上的 AAR 解包读 PT_LOAD）：
    //   1.0.0-alpha01 / alpha02 / beta01 / beta02 → p_align = 0x1000（4 KB，不合格）
    //   1.0.0-rc01 起                            → p_align = 0x4000（16 KB，合格）
    //
    // 正常情况下 compose-bom 2026.05.00 → ui-graphics 1.11.1 已经请求 1.0.1，
    // 不会真解析到 alpha 版。写死是为了让「版本被别的依赖悄悄降到 alpha」这件事
    // 直接变编译期事实——否则它会以 Play Console 打回的形式在发版时才暴露。
    implementation("androidx.graphics:graphics-path:1.0.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3") // 需求 11：光鸭扫码登录二维码渲染

    implementation("androidx.media3:media3-exoplayer:1.9.1")
    implementation("androidx.media3:media3-ui:1.9.1")
    // 双核心 v5：移除 nextlib-media3ext（minCompileSdk 冲突 / 维护成本高），
    // Exo 软解短板改由 mpv 核心兜底（§5.4）。ExoPlayer 回归原生解码 + 解码器回退。

    implementation("androidx.room:room-ktx:2.8.4") // 升 2.8.4 以兼容 Kotlin 2.3 / KSP 2.3.x（2.6.1 的 KSP 处理器跟不上）
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")

    // instrumentation 测试只用最薄的一层：AndroidJUnit4 runner + InstrumentationRegistry。
    // 不引 espresso/compose-test，避免为「能不能播」这一个问题拖进整套 UI 测试依赖。
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ─────────────────────────────────────────────────────────────────────────────
// verifyMpvNativeLibs —— 「libmpv 真的在包里」的构建期门禁
//
// 这个任务存在的唯一理由：缺 libmpv.so 时 CMakeLists 会安静地编一个 stub
// 版 libplayer.so，整个构建**成功**，只有等用户点开视频才看到「未检测到
// libmpv.so：原生库缺失」。那种失败模式已经发生过一次，不能再靠人眼看日志。
//
// 检查项（任一不满足即构建失败）：
//   1) abiFilters 白名单里每个 ABI 的 jniLibs 目录都有 libmpv.so
//   2) libmpv 在运行时 dlopen 时会连带加载的兄弟库也在（缺一个就整串失败，
//      dlerror 仍然只会说 "libmpv.so not found"，最容易误判）
//   3) 目录里没有 libplayer.so —— 那是 CMake 的产物，手工放进去会和产物同名
//
// 有意不放进 CMake：CMake 拿不到 abiFilters 的最终值（AGP 在 configure 之后
// 才决定），而这里能直接读到 android.defaultConfig.ndk.abiFilters。
// ─────────────────────────────────────────────────────────────────────────────
val mpvRequiredSiblings = listOf(
    "libavcodec.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libswresample.so",
    "libswscale.so",
    "libavdevice.so",
    "libc++_shared.so",
)

/** 每个 release ABI 必须成套出现的 .so（libmpv + 它在 Android 上的运行时依赖）。 */
val mpvRequiredPerAbi = listOf("libmpv.so") + mpvRequiredSiblings

val verifyMpvNativeLibs by tasks.registering {
    group = "verification"
    description = "校验 jniLibs/<abi>/ 里的 libmpv.so 及其兄弟库是否成套（缺失即失败）"

    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
    // 与 android.defaultConfig.ndk.abiFilters 同源（都来自 mpvAbis），这样
    // -PmpvAbis=arm64-v8a 时门禁只查 arm64，不会拿没装的 ABI 来卡人。
    val abis = android.defaultConfig.ndk.abiFilters?.toList().orEmpty().ifEmpty { mpvAbis }
    inputs.files(fileTree(jniLibsDir) { include("**/*.so") })
    inputs.property("abis", abis)

    doLast {
        val problems = mutableListOf<String>()
        for (abi in abis) {
            val dir = jniLibsDir.dir(abi).asFile
            if (!dir.isDirectory) {
                problems += "$abi: 目录不存在（$dir）"
                continue
            }
            val present = dir.listFiles { f -> f.isFile }?.map { it.name }?.toSet() ?: emptySet()
            for (name in mpvRequiredPerAbi) {
                if (name !in present) {
                    problems += "$abi: 缺 $name"
                }
            }
            if ("libplayer.so" in present) {
                problems += "$abi: 出现了 libplayer.so —— 它必须由 CMake 产出，" +
                    "放进 jniLibs 会和构建产物重名"
            }
            if (present.any { it.endsWith(".so") } && "libmpv.so" !in present) {
                problems += "$abi: 有兄弟库但没有 libmpv.so（装到一半）"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("libmpv 原生库不完整 —— APK 会退化成 stub 版 libplayer.so，")
                    appendLine("运行期报「未检测到 libmpv.so：原生库缺失」。")
                    appendLine()
                    problems.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("修复（任选其一）：")
                    appendLine("  1) 直接喂 .aar（脚本会自己抽 jni/<abi>/*.so）：")
                    appendLine("     python scripts/assemble-libmpv-jnilibs.py \\")
                    appendLine("       --source libmpv-1.0.0.aar --abis ${abis.joinToString(",")}")
                    appendLine("  2) 喂已解包的 jni 目录：")
                    appendLine("     python scripts/assemble-libmpv-jnilibs.py \\")
                    appendLine("       --source <AAR 解包目录>/jni --abis ${abis.joinToString(",")}")
                    appendLine("  3) 在 CI 里：先跑 build-libmpv 产出 artifact，再跑 build-apk 自动装配。")
                    appendLine("（bash scripts/fetch-libmpv.sh --verify 可查看现有产物）")
                },
            )
        }
        logger.lifecycle(
            "[mpv] jniLibs 校验通过：" +
                abis.joinToString { abi ->
                    "$abi=${jniLibsDir.dir(abi).asFile.listFiles { f -> f.name.endsWith(".so") }?.size ?: 0}"
                } +
                " 个 .so",
        )
    }
}

// 挂在 preBuild 上：任何 assemble* / bundle* 都会先过这道门。
// 只在有 jniLibs 校验意图时触发一次，不额外增加增量构建成本。
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyMpvNativeLibs)
}
