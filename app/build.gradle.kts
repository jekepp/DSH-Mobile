import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// 移动端适配插件（dsh 客户端插件）随 APK 分发
//
// Kernel 在拉起 dsh 之前把它解到 $DSH_HOME/profiles/node_modules/ 并挂上
// $DSH_HOME/cordis.patch.yml，由 dsh 自己加载，App 侧不再注入任何布局 CSS。
//
// 打成 .bin 而不是 .tar.gz 是故意的：AGP 会认出 .gz 扩展名并自动解压改名，
// 把裸 tar 原样塞进 APK（rootfs / seed 已经踩过这个坑，APK 从 58MB 涨到 136MB）。
// ---------------------------------------------------------------------------
val mobileShellAssetsDir = layout.buildDirectory.dir("generated/mobile-shell-assets")

val packMobileShell = tasks.register<Tar>("packMobileShell") {
    description = "把 dsh-client-ui-mobile-shell 打成 assets/mobile-shell.bin"
    from(rootProject.file("plugin/dsh-client-ui-mobile-shell")) {
        into("dsh-client-ui-mobile-shell")
    }
    archiveFileName.set("mobile-shell.bin")
    destinationDirectory.set(mobileShellAssetsDir)
    compression = Compression.GZIP
}

// 把生成的插件资产目录并进 assets。
// 直接给 SourceSet 塞 Provider 会被 AGP 拒绝（它无法判断目录是「生成物」还是「静态文件」），
// 官方给的开关是 gradle.properties 里的 android.sourceset.disallowProvider=false；
// 代价是 AGP 不会自动带上任务依赖，所以下面显式接 dependsOn。
android {
    // 用 directories.add（这里要 String）而不是已废弃的 srcDir(Any)
    sourceSets.getByName("main").assets.directories.add(
        layout.buildDirectory.dir("generated/mobile-shell-assets").get().asFile.absolutePath,
    )
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(packMobileShell) }
tasks.named("preBuild") { dependsOn(packMobileShell) }

android {
    namespace = "com.DSHAndroid.mobile"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.DSHAndroid.mobile"
        minSdk = 29          // Android 10
        targetSdk = 37       // Android 17 (Cinnamon Bun)
        versionCode = 33
        versionName = "0.33.0"
    }

    // 调试签名密钥放进工程内，不用默认的 ~/.android/debug.keystore。
    // 原因：默认位置在用户主目录下，遇到受限环境（沙箱 / 只读主目录 / CI）会直接
    // 「AccessDeniedException: debug.keystore.lock」把打包卡死。
    // 密钥用 keytool 生成，口令固定为 Android 惯例的 android。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 注意：不要给 assets 设 noCompress。AGP 会认出 .gz 并自动解压改名，
    // 结果反而把 106MB 的裸 tar 原样塞进包。让 zip 自己 deflate 即可（约 30MB）。
    packaging {
        // 关键：必须把 jniLibs 里的 lib*.so 真正解到 nativeLibraryDir，
        // 否则 proot 无法从那里 exec（Android 上唯一可 exec 的路径）
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 工作区选择页要观察 Activity 生命周期（从「所有文件访问」设置页回来后刷新权限状态）
    implementation("androidx.lifecycle:lifecycle-runtime-compose")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
