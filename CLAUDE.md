# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

MyRobots is an Android app (Kotlin, Jetpack Compose, Material 3, Room) for managing industrial robots — mainly Kawasaki paint robots (AS language) over telnet: robot registry, telnet/TCP terminal, backups of `.as`/`.pg` files, and an editor for AS code. `KAWASAKI` is the only manufacturer with real functionality; FANUC/ABB/UR can be registered but do nothing yet.

**Kawasaki manuals** (AS Language Reference, E-controller troubleshooting/error codes, K-ROSET, KJ series) are in `Arquivos_Kawasaki/` at the repo root. The folder is git-ignored (large, copyrighted); read the relevant PDF with the `pages` parameter when you need command syntax or error-code meanings.

**`GUIDE.md` is the source of truth for behavior.** It documents every module and screen in detail (in Portuguese), including the navigation route table and a "Pendências / Próximos passos" section per module. When you change how a screen or module behaves, update its section in `GUIDE.md` as well as the code. **`docs/PLANO_V1_2.md`** is the approved roadmap for v1.2 (phases 0, 1, 2.0–2.2); work is done one phase at a time on branch `melhorias/v1.2` with small commits. `.agent/plan.md` is an older generated plan, not authoritative.

## Build and test (Windows)

`java` is not on PATH — use Android Studio's JBR. In PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug                                   # build
.\gradlew.bat assembleDebug testDebugUnitTest --console=plain --continue *> build.log   # full verification
.\gradlew.bat :core:common:testDebugUnitTest --tests "my.robots.core.common.ascode.AsProgramBlocksTest"   # single test class
.\gradlew.bat :core:database:connectedDebugAndroidTest          # Room migration test (needs a phone/emulator)
```

- **Protocol test suite**: `python tools/protocolo/rodar_testes.py` (add `--kroset 127.0.0.1:9105` or `--kroset nao`, `--celular`). It starts a fake Kawasaki controller in Python, runs every JVM test plus the protocol tests against the fake and K-ROSET, and writes `tools/protocolo/relatorios/ultimo.md`. Run it after any change to the terminal, SAVE/LOAD or `RobotCommands`, and read the report instead of the Gradle log. See GUIDE.md section 3.
- Never leave a controller waiting: every SAVE/LOAD goes through `RobotCommands` (`loadFile`/`saveFile`), and a question the controller asks mid-transfer must be answered (it hangs until restarted otherwise).
- Builds need internet; `--offline` fails because the KSP/Room processors aren't cached.
- Redirect long Gradle output to a file instead of piping into `Select-Object -First N`, which closes the pipe early.
- If a build fails because `classes.jar` is "utilizado por outro processo", a daemon (often Android Studio's, after a build-file change) is holding it: run `.\gradlew.bat --stop` and retry.
- Toolchain: Gradle 9.6, AGP 9.4.1, Kotlin 2.2.10, compileSdk 36, minSdk 28, Java 11 target. Dependency versions live in `gradle/libs.versions.toml`.
- JVM unit tests live in `:core:common` (AS parsing). No lint or format config beyond Android defaults.

## Architecture

Multi-module Gradle project (`settings.gradle.kts`):

- `:app` is a thin shell: `MyRobotsApp` (Application) and `MainActivity` (permissions, external file intents, `NavHost` route map).
- `:core:model` (plain data), `:core:database` (Room `AppDatabase` + DAOs + migrations), `:core:network` (`KawasakiTerminalManager`, telnet/TCP), `:core:data` (`RobotRepository`), `:core:designsystem` (`MyRobotsTheme` + shared Compose deps), `:core:common` (`FileUtil`, `ascode.AsProgramBlocks`).
- `:feature:*` holds one area of the app each: splash, robots, backup, codeeditor, dashboard, terminal, project (the cabin screen).

Dependency rules (from `GUIDE.md`):
- A feature may depend on `:core:*` but **never on another feature**. Cross-screen wiring happens only in `MainActivity`'s `NavHost`.
- `:core:model` depends on nothing. `:core:database` and `:core:network` depend only on `:core:model`.
- For data, screens talk only to `RobotRepository`, never to DAOs. The exception is the live terminal: ViewModels that talk to robots get `KawasakiTerminalManager` directly via their factory. Multi-step terminal logic belongs in a `:core:data` class, not a ViewModel.
- A new feature module is created by copying another feature's `build.gradle.kts` and registering it in both `settings.gradle.kts` and `app/build.gradle.kts`.

Wiring and state:
- There is no DI framework. `MyRobotsApp.onCreate` builds the Room DB, the `RobotRepository`, and a single app-wide `KawasakiTerminalManager`, so terminal connections survive screen changes. ViewModels receive these through hand-written `*ViewModelFactory` classes.
- The database is never wiped. Schemas are exported to `core/database/schemas/`; a version bump needs a hand-written migration in `DatabaseMigrations.kt` (`ALL_MIGRATIONS`) plus a case in `MigrationTest`, otherwise the app fails to open the DB.
- No storage permissions. Robot files live in `Documents/MyRobots/<robotDirName>/` via MediaStore, or in a user-picked SAF folder (`RobotFilesStorage` in `:core:data`, behind the `RobotFileStore` interface in `:core:network` so the terminal can use it). The DB is the source of truth: folder sync only imports files and never deletes backups. See GUIDE.md section 14.
- `Robot.loginPassword` is stored encrypted (Android Keystore). `RobotRepository` encrypts on write and decrypts on read, so never read robots straight from `RobotDao`.
- File names that come from the robot (SAVE/LOAD protocol) or from other apps must pass `TransferFileNames.safeName` / `ExternalAsFile`.
- Backups hold the full `.as` text in `Backup.content`. Lists use `BackupSummary`, which has no content, to avoid loading large files, and `MyRobotsApp` enlarges `CursorWindow` for big rows. `robotId = -1` marks a temporary backup opened from outside the app.
- Any code that finds, replaces, removes or copies a `.PROGRAM … .END` block must use `AsProgramBlocks`, which matches the **exact** program name. A `startsWith(".PROGRAM $name")` check also matches `pg10` when looking for `pg1`, which previously deleted programs on save.
- There is no HTTP API; backups (SAVE) and uploads (LOAD) go through the terminal's file-transfer protocol in `KawasakiTerminalManager`.
- `AsCodeViewer` (`:feature:codeeditor`) serves several routes: a whole backup, one `.PROGRAM … .END` block that is spliced back into the backup on save, a read-only variables view built from `.TRANS`/`.REALS`/`.STRINGS`, and external files.

## Conventions

- Code comments, UI strings, `GUIDE.md`, and git commit messages are in **Portuguese**. Match that. Commit subjects are imperative (for example, "Adiciona …" or "Corrige …"), and the body is a bullet list.
- `as` is a Kotlin keyword, so the AS-language package is named `ascode`.
