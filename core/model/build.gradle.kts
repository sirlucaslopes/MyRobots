// Modelos de dados do app (Robô, Backup, Comando Rápido...). Sem telas e sem rede.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "my.robots.core.model"
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
    api(libs.androidx.room.runtime) // as anotações @Entity vêm do Room
}
