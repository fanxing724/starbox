import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名凭据从本地 keystore.properties 读取，不写进代码、不进版本库
val ksPropsFile = rootProject.file("keystore.properties")
val ksProps = Properties().apply {
    if (ksPropsFile.exists()) ksPropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.fanxing.xbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fanxing.xbox"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "0.9.2"
    }

    signingConfigs {
        create("starbox") {
            if (ksPropsFile.exists()) {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("starbox")
        }
        debug {
            // debug 也用同一把正式 key，保证后续版本能覆盖升级
            signingConfig = signingConfigs.getByName("starbox")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 视频播放：Media3/ExoPlayer，Apache 2.0，纯 JVM 无 native 库
    // （不用 mpv —— 那要打包 12MB 的 .so，且构建链复杂）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // PDF 预览用系统自带 android.graphics.pdf.PdfRenderer，零依赖
}
