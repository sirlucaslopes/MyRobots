// Motor 3D do app (Filament): desenho, câmera, .glb gerado e lido, robô montado (RobotAssembly).
// Usado pelo :feature:robot3d (visualizador e montador) e pela cabine 3D da Estação.
// As telas ficam nas features; aqui só View do Android e Kotlin puro (testes no PC).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "my.robots.core.render3d"
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
}

dependencies {
    // RobotModel, Transform etc. aparecem na API daqui
    api(project(":core:kinematics"))

    // Filament: motor 3D do Google (desenho), gltfio (lê .glb) e utils (iniciação, DisplayHelper)
    api(libs.filament.android)
    api(libs.filament.gltfio.android)
    implementation(libs.filament.utils.android)

    testImplementation(libs.junit)
}
