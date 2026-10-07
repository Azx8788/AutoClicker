plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.azx8788.autoclicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.azx8788.autoclicker"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.0"
    }

    buildTypes {
        debug {
            // 固定 debug 签名：CI 注入 ci-debug.keystore 时启用，保证跨构建签名一致
            val ks = rootProject.file("ci-debug.keystore")
            if (ks.exists()) {
                signingConfig = signingConfigs.getByName("debug").apply {
                    storeFile = ks
                }
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
