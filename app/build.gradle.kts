import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 产物文件名：OnlyMoneyNote-release.apk / OnlyMoneyNote-debug.apk
base.archivesName.set("OnlyMoneyNote")

android {
    // 包名 / applicationId 已从 com.dafeng.moneynote 换成 com.dafeng.onlymoneynote：
    // 系统当成两个不同的 App，可以共存。旧包名那个里的数据不会自动过来，
    // 要走「导入导出 → 导出 JSON 备份」再在新 App 里「从 JSON 备份恢复」。
    namespace = "com.dafeng.onlymoneynote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dafeng.onlymoneynote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }

        // ML Kit 的中文 OCR 模型每个 ABI 一份，各 6~11MB，是包体里最大的一块。
        // 绝大多数国产/国际手机都是 arm64，只留它能省掉 ~28MB。
        // 要出全 ABI 包（比如上架 Google Play 或要兼容老设备）就把这行注释掉。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // 密码在根目录 keystore.properties（已 gitignore），仓库公开也不泄露签名密钥。
        // 新机器克隆后先照 README 补这个文件，否则出不了 release 包。
        val ks = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        create("release") {
            storeFile = rootProject.file(ks.getProperty("storeFile", "moneynote-release.jks"))
            storePassword = ks.getProperty("storePassword", "")
            keyAlias = ks.getProperty("keyAlias", "")
            keyPassword = ks.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.mlkit.text.recognition)
    // 中文识别模型：截图/小票是中文，必须显式加，光有 text-recognition 的拉丁模型认不出来
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
}
