import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.androiduse"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.androiduse"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "ARK_API_KEY", "\"${localProps.getProperty("ark.apiKey", "")}\"")
        buildConfigField("String", "ARK_MODEL_ID", "\"${localProps.getProperty("ark.modelId", "")}\"")
        buildConfigField("String", "ARK_BASE_URL", "\"${localProps.getProperty("ark.baseUrl", "https://ark.cn-beijing.volces.com/api/plan/v3")}\"")
        // 按描述定位（spec 2026-09-26）：为空则 tap 不提供 target 形态
        buildConfigField("String", "TYPESAFE_API_KEY", "\"${localProps.getProperty("typesafe.apiKey", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // 1d：端侧中文 OCR，模型打包进 APK，离线（DESIGN §5.3）
    implementation(libs.mlkit.text.recognition.chinese)
    testImplementation(libs.junit)
}

// 按描述定位的离线回放（LocateReplayTest）：把 LOCATE_CASES 透传给测试 JVM，且设了它就不让测试任务被判"最新"而跳过。
tasks.withType<Test>().configureEach {
    System.getenv("LOCATE_CASES")?.let { environment("LOCATE_CASES", it) }
    outputs.upToDateWhen { System.getenv("LOCATE_CASES") == null }
}
