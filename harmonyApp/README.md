# HarmonyOS native KMP integration

`shared` uses CPF-KMP-CMP's `ohosArm64` target and its SQLDelight `NativeSqliteDriver`; it does not run a JVM or bridge to RDB.

The HarmonyOS native host must obtain an app-owned **persistent** absolute directory (not `cacheDir`) and a stable installation/device ID, then construct `DatabaseDriverFactory(databaseDirectory, installationId)`. The factory creates `<databaseDirectory>/deadliner_new_era.db`. An invalid path is an error by design—there is no in-memory fallback, because it would silently discard user data.

Build `:shared:linkDebugSharedOhosArm64` to produce `libDeadlinerCore.so`. The host's NAPI/C++ layer must load that library before exposing Kotlin APIs to ArkTS. This repository intentionally does not add ArkTS UI or NAPI glue code.
