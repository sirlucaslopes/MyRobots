// Tela de Projeto: a cabine com os robôs dispostos como na real, o estado de cada um e o
// editor do layout (Fase 2.1 do plano v1.2).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "my.robots.feature.project"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    // cabine 3D (Filament, robôs montados no Montador)
    implementation(project(":core:render3d"))

    testImplementation(libs.junit)
}
