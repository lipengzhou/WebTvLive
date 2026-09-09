import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

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
        versionCode = 4
        versionName = "0.0.4"

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
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
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
