// Repositório: junta banco local, rede e arquivos numa única porta de entrada para as telas.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "my.robots.core.data"
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
    api(project(":core:database"))
    api(project(":core:network"))
    api(project(":core:common"))
}
