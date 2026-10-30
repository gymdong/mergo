import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = "") = localProps.getProperty(key, default)

android {
    namespace = "app.mergo"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mergo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // API 키는 local.properties에만 둔다. 없으면 LLM 호출만 비활성화된다.
        buildConfigField("String", "LLM_PROVIDER", "\"${localProp("LLM_PROVIDER", "gemini")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${localProp("GEMINI_MODEL", "gemini-flash-latest")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProp("GEMINI_API_KEY")}\"")
        buildConfigField("String", "CLAUDE_MODEL", "\"${localProp("CLAUDE_MODEL", "claude-haiku-5-5")}\"")
        buildConfigField("String", "CLAUDE_API_KEY", "\"${localProp("CLAUDE_API_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
