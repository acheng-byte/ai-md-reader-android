import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 若存在 keystore/keystore.properties 则启用 release 正式签名，否则 release 用 debug 签名兜底
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKeystore) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.mdreader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mdreader.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "1.9.2"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 关闭代码压缩：应用含 @JavascriptInterface 反射桥，且体积已很小，避免误删风险
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    // 排除 POI 等库中重复的 META-INF 文件，防止打包冲突
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Apache POI HWPF — 正确解析旧版 .doc（OLE2）格式，解决乱码问题
    // poi-scratchpad 包含 HWPFDocument，poi 只包含核心模块不含 HWPF
    implementation("org.apache.poi:poi:4.1.2")
    implementation("org.apache.poi:poi-scratchpad:4.1.2")
}
