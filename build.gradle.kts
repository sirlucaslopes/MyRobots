// Arquivo de build da raiz: só declara os plugins usados pelos módulos (sem aplicar aqui).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
}
