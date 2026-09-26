package my.robots.core.database

import androidx.room.migration.Migration

/**
 * Migrações do banco, escritas à mão, uma para cada subida de versão.
 *
 * Como usar ao mudar uma entidade:
 * 1. Suba a versão em AppDatabase.
 * 2. Compile: o Room grava o schema novo em core/database/schemas/<versão>.json.
 * 3. Escreva aqui um `Migration(antiga, nova)` com o SQL que leva do schema antigo ao
 *    novo (compare os dois .json) e inclua na lista ALL_MIGRATIONS.
 * 4. Acrescente o caso no MigrationTest (androidTest deste módulo).
 *
 * Sem a migração, o app falha ao abrir o banco em vez de apagar os dados do usuário.
 */
val ALL_MIGRATIONS: Array<Migration> = emptyArray()
