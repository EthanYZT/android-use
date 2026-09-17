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
        buildConfigField("String", "ARK_BASE_URL", "\"${localProps.getProperty("ark.baseUrl", "https://ark.cn-beijing.volces.com/api/v3")}\"")
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
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
