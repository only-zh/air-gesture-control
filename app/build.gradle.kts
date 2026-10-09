plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gesturecontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gesturecontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"

        // MediaPipe 只提供 arm64-v8a / armeabi-v7a / x86，真机覆盖前两者即可，
        // 去掉 x86 可以少带约 21MB 的 so。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * 签名密钥。
     *
     * 本地：`tools/setup-toolchain.sh` 会在 `app/keystore/debug.keystore` 生成一个
     * 标准 debug 密钥（口令 `android`）。
     *
     * CI：GitHub Actions 把仓库 Secret 里的密钥解码到**同一个路径**，
     * 口令通过环境变量传入。路径和口令都对齐，所以 CI 出的包和本地出的包
     * 签名一致，用户可以直接覆盖升级。
     *
     * 密钥文件不存在时**不注册签名配置**：这样别人 fork 下来、或者 CI 里没有
     * 配 Secret 时，`assembleDebug` 只是产出未签名的包，而不是直接构建失败。
     * 注意发版流程里必须显式校验 Secret 是否存在，否则会发出去一个装不上的包。
     */
    val keystoreFile = file("keystore/debug.keystore")

    signingConfigs {
        if (keystoreFile.exists()) {
            create("local") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("local")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.findByName("local")
            isMinifyEnabled = false
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 一阶段：离线语音识别（自带 JNA 依赖）
    implementation("com.alphacephei:vosk-android:0.3.75")

    // 二阶段：手部关键点检测。必须锁 0.10.14 —— 0.10.22 及之后的 AAR
    // 不再包含 libmediapipe_tasks_vision_jni.so，装到手机上会 UnsatisfiedLinkError。
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    // 纯逻辑模块（命令匹配、手势轨迹判定）用 JVM 单元测试验证
    testImplementation("junit:junit:4.13.2")
}
