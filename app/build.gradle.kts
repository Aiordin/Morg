import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// —— 构建信息（详情页展示，见 ui/BuildInfoScreen.kt）——
//
// 在配置期读一次 git。这些值会进 BuildConfig，所以只要 hash 变了，
// buildConfig 任务就会重跑 —— debug 增量构建也能跟上最新提交。
// 没有 git（归档源码包、CI 浅克隆失败）时全部退回 "unknown"，不影响构建。
fun gitOutput(vararg args: String): String = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim()
}.getOrDefault("")

val gitHash = gitOutput("rev-parse", "--short=8", "HEAD")
val gitDirty = gitOutput("status", "--porcelain").isNotEmpty()
val buildStamp: String = runCatching {
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault("unknown")

// —— 版本与签名 ——
//
// CI 从 tag 解析出版本号，用 -PmorgVersionName / -PmorgVersionCode 注入；
// 本地不传时就是下面这两个缺省值。
val morgVersionName: String = providers.gradleProperty("morgVersionName").orElse("0.1.0").get()
val morgVersionCode: Int = providers.gradleProperty("morgVersionCode").orElse("1").get().toInt()

// release 签名从环境变量读，没有就退回 debug 签名（可直接安装，但不是发布身份）。
// MORG_KEYSTORE_FILE 建议用绝对路径：相对路径会相对 app/ 解析。
// CI 会把 base64 的 keystore 解码到 $RUNNER_TEMP 再把绝对路径传进来。
val releaseKeystore: String? = System.getenv("MORG_KEYSTORE_FILE")
val hasReleaseSigning: Boolean = !releaseKeystore.isNullOrBlank() && file(releaseKeystore).exists()

// compileSdk 也要进 BuildConfig。注意不能叫 `compileSdkVersion` —— 那是 AGP 的
// deprecated 扩展属性（String?），会覆盖局部变量导致类型不匹配。
val morgCompileSdk = 35

android {
    namespace = "aiordin.morg"
    compileSdk = morgCompileSdk

    defaultConfig {
        applicationId = "aiordin.morg"
        minSdk = 29
        targetSdk = 34
        versionCode = morgVersionCode
        versionName = morgVersionName

        buildConfigField("String", "GIT_HASH", "\"${gitHash.ifBlank { "unknown" }}\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
        buildConfigField("int", "COMPILE_SDK", "$morgCompileSdk")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystore))
                storePassword = System.getenv("MORG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MORG_KEY_ALIAS")
                keyPassword = System.getenv("MORG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有发布密钥时用 debug 签名：产出的 APK 能安装、能试用，
            // 但不能作为正式发版身份（升级到正式签名时需要卸载重装）。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 详情页要读 VERSION_NAME / BUILD_TYPE / 上面四个自定义字段。
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
