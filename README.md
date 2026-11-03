# Deadliner NEW ERA Core Preview

This repository hosts the isolated Kotlin Multiplatform domain, persistence, and synchronization core described in `specs/kmp-persistence-sync/`. HarmonyOS uses the CPF-KMP-CMP native toolchain.

## Current Foundation

`shared/src/commonMain/kotlin/com/deadliner/domain` now contains the stable cross-platform domain contract:

- Independent `Task` / `TaskSubtask` and `Habit` / `HabitRecord` aggregates.
- `Category`, sync versioning/change sets, AI proposals, memory snapshots, and user tiers.
- No `DDLItem`, `Habit.ddlId`, `habit_ddl_link`, or Harmony JVM runtime target. HarmonyOS is an `ohosArm64` native library.

SQLDelight schema and repositories implement these models without redefining them. The reconciled Android/iOS final DDL is documented in `specs/kmp-persistence-sync/android-ios-reconciliation.md`. The initial integration-facing [API reference](docs/API_REFERENCE.md) covers repositories, synchronization, snapshots, and platform driver factories.

## Lint

GitHub Actions runs source-only Kotlin lint with the pinned detekt CLI:

```bash
bash runscript/lint.sh
```

This requires JDK 21, curl and shasum. The first run downloads a checksum-verified
linter from Maven Central; subsequent runs use `build/tools/detekt`. It scans
public Kotlin source sets (including tests and Lifi stubs) and Kotlin build
scripts without evaluating Gradle, resolving application dependencies, compiling,
or requiring Android, Xcode or HarmonyOS SDKs. Type-resolution rules are not run.
Platform builds and runtime tests remain separate local validation steps below.

Reports are written to `build/reports/detekt` and uploaded by CI even on lint
failure. Existing findings are recorded in `config/detekt/baseline.xml`; new
findings fail CI. Baseline changes should be reviewed rather than automatically
regenerated on every run. To deliberately refresh it after reviewing findings:

```bash
bash runscript/lint.sh --create-baseline
```

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

For complete Native stability acceptance (three unfiltered Gradle suites with
verified XML, then ten suites in one process), run:

```bash
runscript/verifyNativeRegression.sh
```

CPF Native `2.2.21-0.3.0` has an exception ShadowStack restoration defect. The
build selects a fingerprint-guarded repaired SDK under `build/native-toolchains`
before applying KGP; the original `.konan` installation is unchanged. Unknown
compiler fingerprints fail closed. On a fresh machine, first run
`./gradlew :shared:downloadKotlinNativeDistribution`. Toolchain upgrades require
rechecking/removing this repair and repeating full regression. See the
[diagnosis](specs/native-test-stability/diagnosis.md) and
[acceptance results](specs/native-test-stability/validation.md).

### Stable build metadata

The embedded core identifier uses `DeadlinerCore-<commit6>-<branch>@<user>`,
where `commit6` is the first six characters of the commit hash.
Metadata generation is up to date when its inputs have not changed, and never
rewrites identical content. The legacy `compileDate` and `compiledAtUtc` fields
use the HEAD commit's committer time rather than the wall clock. Uncommitted
source edits still trigger compilation normally but retain the HEAD identifier.

CI/source archives can override `deadlinerCoreCommit`,
`deadlinerCoreCompiledAtUtc` (an ISO-8601 instant), `deadlinerCoreCompileDate`,
`deadlinerCoreCompileBranch`, and `deadlinerCoreCompileUserName` using `-P`.
Without Git, the default revision is `unknown` and the default time is the Unix
epoch. For repeatable builds, keep overrides stable for the same source revision.

### One-command local deployment

Build every release binary and put it directly in the matching local client
repository with:

```bash
runscript/buildAndDeploy.sh
```

The zero-argument command auto-detects the sibling repositories linked under
`ProjectDeadliner` and deploys verified outputs to:

- Android: `Deadliner/.deadliner-core/kmp/{debug,release}/deadliner-core.aar`
- iOS: `Deadliner-iOS/Vendor/KMP/shared.xcframework`
- HarmonyOS: `DeadlinerNEXT/entry/libs/arm64-v8a/libDeadlinerCore.so`

Each destination is replaced only after the new artifact has been fully staged;
the script then compares it with the build output. Use `--dry-run` to inspect the
resolved paths, `--no-build` to redeploy existing outputs, or `--skip-android`,
`--skip-ios`, and `--skip-harmony` for a partial run. Repository locations can be
overridden with the corresponding `--*-repo PATH` option.

## Phase 1/2 acceptance

Run the repeatable shared-core acceptance matrix with:

```bash
zsh runscript/verifyPhase12Acceptance.sh
```

It compiles Android, iOS Simulator and macOS sources; links the iOS Simulator framework; runs macOS SQLite/domain tests; verifies SQLDelight migration history; and links the HarmonyOS `ohosArm64` shared library. The current CPF Kotlin/Native toolchain links the iOS framework successfully, but its `iosSimulatorArm64Test` executable fails to link on Xcode 26.5 with an unresolved `_Kotlin_KonanStartStub`. This is documented as a toolchain blocker rather than a passing iOS runtime test.

## Closed-source binary releases

Lifi is maintained in the [private repository](https://github.com/DeadlinerStudio/Lifi).
A normal clone of DeadlinerCore builds without access to it. Public AI models,
`AIComponent`, `LifiRuntime`, persistence and platform bridges remain in this repo.
The public fallback preserves the host-facing API; AI calls throw
`LifiUnavailableException` (`LIFI_UNAVAILABLE`) and never call providers or execute
tools. Check `LifiAvailability.isAvailable` to hide or disable AI entry points.

Build modes (Gradle property):

- `-PlifiMode=auto` (default): use the private checkout if installed, otherwise fallback.
- `-PlifiMode=stub`: force the public fallback even on a maintainer's machine.
- `-PlifiMode=private`: require the private implementation; fail if missing/incompatible.

Maintainers with private repo access can install the revision pinned in
`lifi.revision`:

```bash
runscript/setupLifi.sh
./gradlew -PlifiMode=private :shared:macosArm64Test
# Verify the public build explicitly:
./gradlew -PlifiMode=stub :shared:compileDebugKotlinAndroid :shared:macosArm64Test
```

The private checkout is ignored at `.private/lifi`; it is not a required submodule.
No GitHub credentials, private fetches or extra setup are needed for public builds.
The private source overlay shares Core's KMP compilation to avoid a circular module
dependency. Private provider/agent tests run only in private mode. To upgrade,
commit/push private changes and update `lifi.revision` in the matching Core change.

Build all client artifacts on a trusted macOS machine, then publish only binaries
to a **private** GitHub Release. The release script forces private mode:


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


## IDE builds including private Lifi

Install the pinned private checkout once with `runscript/setupLifi.sh`. Android
Studio Run/Debug and APK/AAB builds, Xcode Run/Archive, and DevEco Run/Debug/HAP
builds invoke Core automatically. These app entry points require private Lifi by
default and stop with a setup hint when it is missing. For an explicitly public
app build use `DEADLINER_LIFI_MODE=stub` (Android also accepts
`-PdeadlinerLifiMode=stub`). Standalone Core builds retain `lifiMode=auto`.

Debug uses a debug AAR/XCFramework/SO; Release uses release binaries. Android
keeps variant outputs separate and tracks private Kotlin sources, resources and
contract/version inputs. Xcode passes its configuration; DevEco reads Hvigor's
build mode for both default and prod targets. Private code is compiled from its
original local path, so native debug symbols refer to the installed source.
With the current CPF OHOS toolchain, Debug uses optimized linking with full DWARF
to remove unavailable runtime imports. Source breakpoints remain available;
some optimized local variables may be unavailable. OHOS linking rejects unresolved
Kotlin runtime symbols before a library can be deployed.

The same build/deploy behavior is available manually:

```bash
runscript/buildAndDeploy.sh --configuration debug --lifi-mode private
runscript/buildAndDeploy.sh --configuration release --lifi-mode private
```

`--no-build` copies existing outputs and is intended for manual recovery only;
IDE builds always run Gradle. `--dry-run` checks paths without building/deploying.
