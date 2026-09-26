// Comunicação com o robô: terminal TCP/telnet (Kawasaki).
plugins {
    alias(libs.plugins.android.library)
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
}
