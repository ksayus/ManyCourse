plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tof.manycourse"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tof.manycourse"
        minSdk = 31
        targetSdk = 37

        /**
         * 版本号可以用 Gradle 属性覆盖，CI 就是这么做的（见 `.github/workflows/android-ci.yml`）：
         * ```
         *   -PversionName=1.0.0.4 -PversionCode=57
         * ```
         * `versionName` 取 git tag、`versionCode` 取 GitHub run number ——
         * 这样每个 tag 产出的包版本号都不一样，不会出现"装不上、提示已安装更高版本"。
         * 本地不传参数时用下面的默认值。
         */
        versionCode = (providers.gradleProperty("versionCode").orNull ?: "1").toInt()

        // 第一个版本位是大版本,比如重构UI界面,增加重大功能
        // 第二个版本位是小版本更新,比如添加性能
        // 第三个版本位是Bug修复,数字代表已经修复的Bug数量
        // 第四个版本位是支持的学校数量
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * 发布签名（**可选**）：密钥信息不写进仓库，从 Gradle 属性读 ——
     * ```
     *   -Pmanycourse.storeFile=release.p12 -Pmanycourse.storePassword=… \
     *   -Pmanycourse.keyAlias=manycourse   -Pmanycourse.keyPassword=…
     * ```
     * 也可以长期放在 `~/.gradle/gradle.properties`（不进仓库）。
     * CI 由 GitHub Secrets 生成这个文件，构建完立即删除。
     *
     * **没配密钥时完全不创建签名配置**：`assembleRelease` 照旧产出
     * `app-release-unsigned.apk`，本地开发与 PR 校验都不受影响。
     *
     * 路径解析：相对路径按**根目录**算（`-Pmanycourse.storeFile=release.p12` 指仓库根下的
     * release.p12），不是按 `app/` 模块目录 —— 否则同一个命令行在根目录跑和在模块里跑会指向两个位置。
     */
    signingConfigs {
        val storeFilePath = providers.gradleProperty("manycourse.storeFile").orNull
        val storeFileResolved = storeFilePath?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
        when {
            storeFileResolved == null -> Unit // 没配密钥：正常路径，什么都不做

            // 配了路径但文件不在（密钥被删/被挪/在别的机器上）：
            // 降级成未签名构建并打印原因，而不是让 validateSigningRelease 抛一句英文报错
            !storeFileResolved.exists() -> logger.warn(
                "⚠️  签名密钥不存在：$storeFileResolved —— 本次构建产出未签名的 release 包"
            )

            else -> create("release") {
                storeFile = storeFileResolved
                storePassword = providers.gradleProperty("manycourse.storePassword").orNull
                keyAlias = providers.gradleProperty("manycourse.keyAlias").orNull
                keyPassword = providers.gradleProperty("manycourse.keyPassword").orNull
            }
        }
    }

    buildTypes {
        release {
            // 有密钥才启用签名；没有就退回未签名包（不报错）
            signingConfig = signingConfigs.findByName("release")

            // 开启 R8 优化（移除无用代码、内联、裁剪资源）。
            // 对 Compose 应用性能影响显著：debug 包含大量调试断言且未优化，
            // 流畅度体验应以 release 包为准。
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.recyclerview)
    implementation(libs.haze)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}