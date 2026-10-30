import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

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

// 한국어 모델은 풀면 252MB라 저장소에 넣지 않는다. APK를 만들 때 한 번 받아 assets에 푼다.
val voskModel = "vosk-model-small-ko-0.22"
val voskModelMd5 = "fa8029a173787a159e0e72fe6135f890"

val prepareVoskModel by tasks.registering {
    val zip = rootProject.file(".vosk/$voskModel.zip")
    val target = file("src/main/assets/model-ko")
    outputs.file(File(target, "uuid"))
    doLast {
        if (!zip.exists()) {
            logger.lifecycle("Vosk 모델을 내려받습니다 (약 83MB)")
            zip.parentFile.mkdirs()
            val partial = File(zip.path + ".part")
            uri("https://alphacephei.com/vosk/models/$voskModel.zip").toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            partial.renameTo(zip)
        }
        val md5 = MessageDigest.getInstance("MD5").digest(zip.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(md5 == voskModelMd5) { "Vosk 모델 체크섬이 다릅니다. ${zip.path}를 지우고 다시 빌드하세요." }
        target.deleteRecursively()
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val out = File(target, entry.name.substringAfter('/'))
                out.parentFile.mkdirs()
                archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        File(target, "uuid").writeText(voskModel)
    }
}

tasks.matching { it.name.matches(Regex("merge\\w*Assets")) }.configureEach {
    dependsOn(prepareVoskModel)
}

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
        buildConfigField("String", "GEMINI_MODEL", "\"${localProp("GEMINI_MODEL", "gemini-3.5-flash-lite")}\"")
        buildConfigField("String", "GEMINI_SPEECH_MODEL", "\"${localProp("GEMINI_SPEECH_MODEL", "gemini-flash-lite-latest")}\"")
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
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
