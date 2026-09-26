// Banco de dados local (Room): tabelas e consultas (DAOs).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.androidx.room)
}

// Grava o schema de cada versão do banco em schemas/ (usado pelas migrações e pelo teste de migração).
room {
    schemaDirectory("$projectDir/schemas")
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Teste de migração (roda no celular: :core:database:connectedDebugAndroidTest).
    // O plugin do Room já entrega os schemas/ ao MigrationTestHelper.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
}
