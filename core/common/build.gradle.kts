// Utilitários pequenos usados por vários módulos (ex.: nome de arquivo).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "my.robots.core.common"
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
    implementation(libs.androidx.core.ktx)
}
