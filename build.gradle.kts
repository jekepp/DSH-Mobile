// AGP 9 起 Kotlin 支持内置（AGP 9.4.0 自带 KGP 2.2.10），不再需要 kotlin-android 插件；
// 但 Compose 编译器插件仍需显式声明，且版本必须与内置 KGP 一致。
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
