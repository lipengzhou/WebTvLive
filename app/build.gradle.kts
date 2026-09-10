import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 版本号在此集中定义，供 defaultConfig 与产物命名共用，保证 APK 文件名里的版本与内置版本一致。
val webtvliveVersionCode = 6
val webtvliveVersionName = "0.0.6"

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun releaseProperty(name: String): String? =
    providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)

val releaseSigningPropertyNames = listOf(
    "WEBTVLIVE_RELEASE_STORE_FILE",
    "WEBTVLIVE_RELEASE_STORE_PASSWORD",
    "WEBTVLIVE_RELEASE_KEY_ALIAS",
    "WEBTVLIVE_RELEASE_KEY_PASSWORD",
)
val releaseSigningProperties = releaseSigningPropertyNames.associateWith(::releaseProperty)
val missingReleaseSigningProperties = releaseSigningProperties
    .filterValues { it.isNullOrBlank() }
    .keys

fun requestsReleaseArtifact(taskPath: String): Boolean {
    val taskName = taskPath.substringAfterLast(':').lowercase()
    if (taskName in setOf("assemble", "build", "bundle", "publish")) return true
    if ("release" !in taskName) return false
    return listOf("assemble", "bundle", "install", "package", "publish")
        .any(taskName::startsWith)
}

val releaseArtifactRequested = gradle.startParameter.taskNames.any(::requestsReleaseArtifact)
if (releaseArtifactRequested && missingReleaseSigningProperties.isNotEmpty()) {
    throw GradleException(
        "正式构建缺少签名配置：${missingReleaseSigningProperties.joinToString()}。" +
            "请通过环境变量、Gradle property 或 local.properties 配置后重试。",
    )
}

val releaseStoreFilePath = releaseSigningProperties.getValue("WEBTVLIVE_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperties.getValue("WEBTVLIVE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperties.getValue("WEBTVLIVE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperties.getValue("WEBTVLIVE_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = missingReleaseSigningProperties.isEmpty()

if (releaseArtifactRequested && hasReleaseSigning && !file(releaseStoreFilePath!!).isFile) {
    throw GradleException(
        "正式构建签名文件不存在：WEBTVLIVE_RELEASE_STORE_FILE=$releaseStoreFilePath",
    )
}

android {
    namespace = "com.lipengzhou.webtvlive"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lipengzhou.webtvlive"
        minSdk = 28
        targetSdk = 37
        versionCode = webtvliveVersionCode
        versionName = webtvliveVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "APP_UPDATES_ENABLED", "false")
        }
        release {
            buildConfigField("boolean", "APP_UPDATES_ENABLED", "true")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
            }
        }
    }
    flavorDimensions += "engine"
    productFlavors {
        create("gecko") {
            dimension = "engine"
            versionNameSuffix = "-gecko"
            buildConfigField("String", "UPDATE_ENGINE", "\"gecko\"")
        }
        create("webview") {
            dimension = "engine"
            versionNameSuffix = "-webview"
            buildConfigField("String", "UPDATE_ENGINE", "\"webview\"")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = true
        // 依赖/Gradle「有新版本可用」是随上游发布漂移的信息性提示，会让 CI 无规律地
        // 挂在 warningsAsErrors 上；版本升级在本项目是审慎的手动决策，故关闭这些检查。
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
    // 统一产物命名为 webtvlive-<flavor>-<version>-<abi>-<buildType>.apk，
    // 让下载后的安装包不看目录也能一眼分辨内核、版本、架构和构建类型（debug/release 均生效）。
    onVariants { variant ->
        val flavor = variant.flavorName ?: return@onVariants
        val buildType = variant.buildType ?: return@onVariants
        for (output in variant.outputs) {
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: continue
            output.outputFileName.set(
                "webtvlive-$flavor-$webtvliveVersionName-$abi-$buildType.apk",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    "geckoImplementation"(libs.geckoview)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
