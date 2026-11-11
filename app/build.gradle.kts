plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zongyi.box"
    compileSdk = 36

    // Notix SDK 传递依赖把 kotlin-stdlib 拉到 2.1.0，超出本项目编译器(2.0.21)能读取的
    // 元数据版本，会报 "Unresolved reference 'Pair'" 等 stdlib 错误，这里强制对齐
    configurations.all {
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    }

    defaultConfig {
        applicationId = "com.zongyi.box"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("zongyibox.jks")
            storePassword = "***REMOVED***"
            keyAlias = "zongyibox"
            keyPassword = "***REMOVED***"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
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

    // 播放器：GSYVideoPlayer（GitHub 最活跃的开源播放器库），内核为 Media3/ExoPlayer
    implementation("io.github.carguo:gsyvideoplayer-java:14.0.0")
    implementation("io.github.carguo:gsyvideoplayer-exo2:14.0.0")

    // 图片
    implementation("io.coil-kt:coil:2.7.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 广告变现：Monetag（Notix 内核）安卓 SDK，支持插屏 + Push，收益支持 USDT 提现
    implementation("co.notix:android-sdk:0.1.+")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
