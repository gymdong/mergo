import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun local(name: String, default: String = ""): String =
    localProperties.getProperty(name, default).trim()

android {
    namespace = "com.mergo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mergo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // LLM 교체는 local.properties의 LLM_PROVIDER 값 하나로 한다 (gemini | claude).
        buildConfigField("String", "LLM_PROVIDER", "\"${local("LLM_PROVIDER", "gemini")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${local("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${local("GEMINI_MODEL", "gemini-3.5-flash-lite")}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${local("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "CLAUDE_MODEL", "\"${local("CLAUDE_MODEL", "claude-haiku-5-5")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
