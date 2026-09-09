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

val releaseStoreFilePath = releaseProperty("WEBTVLIVE_RELEASE_STORE_FILE")
val releaseStorePassword = releaseProperty("WEBTVLIVE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseProperty("WEBTVLIVE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseProperty("WEBTVLIVE_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.lipengzhou.webtvlive"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lipengzhou.webtvlive"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.0.3"

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
