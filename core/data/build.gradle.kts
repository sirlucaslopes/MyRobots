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
        minSdk = 29
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

    // pasta escolhida pelo usuário (Storage Access Framework)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
}

// Testes do protocolo (tools/protocolo/rodar_testes.py): o endereço do controlador falso, o do
// K-ROSET e a pasta dos registros chegam como -P e viram propriedades do teste. Sem elas, os
// testes do protocolo ficam "pulados" e o build normal não depende de rede.
tasks.withType<Test>().configureEach {
    listOf("protocolo.falso", "protocolo.kroset", "protocolo.saida").forEach { key ->
        project.findProperty(key)?.let { systemProperty(key, it.toString()) }
    }
}
