// Cinemática de robôs: matemática 3D, modelo do robô (peças e eixos), cinemática direta e inversa.
// Kotlin puro (sem telas, sem Android nas classes), para os testes rodarem no PC.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "my.robots.core.kinematics"
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
    testImplementation(libs.junit)
}
