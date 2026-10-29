plugins {
    // AGP 升到 8.10.0：KSP 2.3.11 不仅调用 AGP 8.9.0 才有的 addKspConfigurations API，
    // 还硬编码了 minAgpVersion=8.10.0 的下限（实测 8.9.0 仍报 "minimum supported AGP is 8.10.0"）。
    id("com.android.application") version "8.10.0" apply false
    // Kotlin/Compose 升到 2.3.20：nextlib 1.9.1-0.11.0 用 Kotlin 2.3.x 编译（metadata 2.3.0），
    // 项目原 2.0.21 编译器只认 2.0.0，必须升编译器才能消费 nextlib。
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
