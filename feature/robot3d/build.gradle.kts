// Telas do 3D: Visualizador 3D (teste) e Montador de robô. O motor fica no :core:render3d.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "my.robots.feature.robot3d"
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
    // o motor 3D e a cinemática (o :core:render3d expõe o :core:kinematics e o Filament)
    implementation(project(":core:render3d"))

    testImplementation(libs.junit)
}
