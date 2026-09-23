// Comunicação com o robô: terminal TCP (Kawasaki) e API HTTP (Retrofit).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "my.robots.core.network"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.android)
    api(libs.retrofit)
    api(libs.converter.moshi)
    api(libs.okhttp)
    api(libs.moshi.kotlin)
    "ksp"(libs.moshi.kotlin.codegen)
}
