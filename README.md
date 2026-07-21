# Deadliner NEW ERA Core Preview

This repository hosts the isolated Kotlin Multiplatform domain, persistence, and synchronization core described in `specs/kmp-persistence-sync/`. HarmonyOS uses the CPF-KMP-CMP native toolchain.

## Current Foundation

`shared/src/commonMain/kotlin/com/deadliner/domain` now contains the stable cross-platform domain contract:

- Independent `Task` / `TaskSubtask` and `Habit` / `HabitRecord` aggregates.
- `Category`, sync versioning/change sets, AI proposals, memory snapshots, and user tiers.
- No `DDLItem`, `Habit.ddlId`, `habit_ddl_link`, or Harmony JVM runtime target. HarmonyOS is an `ohosArm64` native library.

SQLDelight schema and repositories implement these models without redefining them. The reconciled Android/iOS final DDL is documented in `specs/kmp-persistence-sync/android-ios-reconciliation.md`. The initial integration-facing [API reference](docs/API_REFERENCE.md) covers repositories, synchronization, snapshots, and platform driver factories.

## Build

The CPF toolchain requires JDK 17 or newer. The wrapper uses Tencent's Gradle distribution mirror, Aliyun mirrors for public Maven/Google/Gradle-plugin dependencies, and CPF's Maven repository only for its Harmony-compatible Kotlin, Kotlinx, SQLDelight, and Touchlab variants. Compile the shared module with:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  ./gradlew :shared:macosArm64Test --no-daemon --console=plain
```

The only preview database name is `deadliner_new_era.db`. It is intentionally separate from every v5 database.

On an Apple Silicon Mac, run the real SQLite integration suite with:

```bash
bash runscript/testMacDatabase.sh
```

## Phase 1/2 acceptance

Run the repeatable shared-core acceptance matrix with:

```bash
zsh runscript/verifyPhase12Acceptance.sh
```

It compiles Android, iOS Simulator and macOS sources; links the iOS Simulator framework; runs macOS SQLite/domain tests; verifies SQLDelight migration history; and links the HarmonyOS `ohosArm64` shared library. The current CPF Kotlin/Native toolchain links the iOS framework successfully, but its `iosSimulatorArm64Test` executable fails to link on Xcode 26.5 with an unresolved `_Kotlin_KonanStartStub`. This is documented as a toolchain blocker rather than a passing iOS runtime test.

## Closed-source binary releases

The Lifi implementation remains local and is deliberately excluded from Git. Build
all client artifacts on a trusted macOS machine, then publish only binaries to a
**private** GitHub Release:

```bash
runscript/releaseBinaries.sh v0.2.0
# Inspect build/releases/v0.2.0, then publish the same artifacts:
runscript/releaseBinaries.sh v0.2.0 --upload
```

The generated release contains an Android AAR, an Apple XCFramework ZIP, the
HarmonyOS arm64 shared library, and SHA-256 checksums in `manifest.json` and
`checksums-sha256.txt`. The upload command refuses public repositories to avoid
exposing closed-source binaries. iOS consumers can use the XCFramework ZIP from
a binary-only Swift Package; Android can consume the AAR directly or through a
private Maven registry; HarmonyOS should download the matching `.so` and verify
its checksum before copying it into its native-library directory.

## Boundaries

- `shared` owns the domain contract now, then schema, queries, repositories, changelog and LWW synchronization orchestration.
- Android and iOS own SQLDelight drivers. HarmonyOS uses CPF's `ohosArm64` target and SQLDelight `NativeSqliteDriver`; its NAPI host only loads the native shared library.
- Network transport and remote business-table application are ports, not HTTP implementations.
