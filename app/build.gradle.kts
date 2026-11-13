import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // AppFunctions（Android MCP）：@AppFunction 注解 → schema 生成
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zongyi.box"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zongyi.box"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    // 签名口令不落仓库：从本地 keystore.properties 或环境变量读取。
    // 缺失时 release 不签名（产出 app-release-unsigned.apk），debug 构建不受影响。
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(key: String, env: String): String? =
        keystoreProps.getProperty(key) ?: System.getenv(env)

    signingConfigs {
        val storePath = signingValue("storeFile", "SIGNING_STORE_FILE")
        val storePass = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
        val alias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
        val keyPass = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
        if (storePath != null && storePass != null && alias != null && keyPass != null) {
            create("personal") {
                storeFile = rootProject.file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("personal")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        // 更新检查需要读取本地 versionCode/versionName 做比较
        buildConfig = true
    }
    testOptions {
        // 单测运行在 JVM，android.util.Log 等框架方法返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 网络 + 解析
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.google.code.gson:gson:2.11.0")

    // Android MCP：AppFunctions（Google 官方规范，Android 16+ 由系统索引，AI Agent 可发现并调用）
    // alpha10+ 要求 compileSdk 37/AGP 9.1；alpha08 兼容 compileSdk 36 + AGP 8.9.1
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha08")
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha08")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha08")

    // 播放器：GSYVideoPlayer（GitHub 最活跃的开源播放器库），内核为 Media3/ExoPlayer
    implementation("io.github.carguo:gsyvideoplayer-java:14.0.0")
    implementation("io.github.carguo:gsyvideoplayer-exo2:14.0.0")

    // 图片
    implementation("io.coil-kt:coil:2.7.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // JVM 单测里 org.json 被 android.jar 桩替换，用真实实现跑 AiBridge 协议测试
    testImplementation("org.json:json:20240303")
}
