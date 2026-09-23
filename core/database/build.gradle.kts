// Banco de dados local (Room): tabelas e consultas (DAOs).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "my.robots.core.database"
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
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.kotlinx.coroutines.core)
    "ksp"(libs.androidx.room.compiler)
}
