plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
}

android {
    namespace = "dev.catandbunny.ai_companion"
    compileSdk = 36

    // Читаем настройки из local.properties
    val localPropertiesFile = rootProject.file("local.properties")
    var openAiApiKey = ""
    var mcpServerHost = "45.14.165.53"
    var mcpServerPort = 8080
    var mcpServerHostLocal = ""
    var mcpServerPortLocal = 0
    // 10.0.2.2 = хост только в эмуляторе; для реального устройства в local.properties укажи IP ПК, напр. http://192.168.0.16:5001
    var ragServiceUrl = "http://10.0.2.2:5001"

    if (localPropertiesFile.exists()) {
        localPropertiesFile.readLines().forEach { line ->
            when {
                line.startsWith("OPENAI_API_KEY=") -> {
                    openAiApiKey = line.substringAfter("=").trim()
                }
                line.startsWith("MCP_SERVER_HOST=") -> {
                    mcpServerHost = line.substringAfter("=").trim()
                }
                line.startsWith("MCP_SERVER_PORT=") -> {
                    mcpServerPort = line.substringAfter("=").trim().toIntOrNull() ?: 8080
                }
                line.startsWith("MCP_SERVER_HOST_LOCAL=") -> {
                    mcpServerHostLocal = line.substringAfter("=").trim()
                }
                line.startsWith("MCP_SERVER_PORT_LOCAL=") -> {
                    mcpServerPortLocal = line.substringAfter("=").trim().toIntOrNull() ?: 0
                }
                line.startsWith("RAG_SERVICE_URL=") -> {
                    ragServiceUrl = line.substringAfter("=").trim().ifEmpty { ragServiceUrl }
                }
            }
        }
    }

    defaultConfig {
        applicationId = "dev.catandbunny.ai_companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Добавляем настройки в BuildConfig
        buildConfigField("String", "OPENAI_API_KEY", if (openAiApiKey.isNotEmpty()) "\"$openAiApiKey\"" else "\"\"")
        buildConfigField("String", "MCP_SERVER_HOST", "\"$mcpServerHost\"")
        buildConfigField("int", "MCP_SERVER_PORT", "$mcpServerPort")
        buildConfigField("String", "MCP_SERVER_HOST_LOCAL", "\"$mcpServerHostLocal\"")
        buildConfigField("int", "MCP_SERVER_PORT_LOCAL", "$mcpServerPortLocal")
        buildConfigField("String", "RAG_SERVICE_URL", "\"$ragServiceUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    
    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}