import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The core build identifier is embedded in every KMP artifact as:
 * `DeadlinerCore-<commit6>-<compileBranch>@<compileUserName>`.
 *
 * Release automation can override any generated component with a Gradle property, which keeps
 * CI artifacts reproducible while local builds retain useful origin information.
 */
private fun gitMetadata(projectDirectory: File, vararg arguments: String): String? = try {
    val process = ProcessBuilder(listOf("git", "-C", projectDirectory.absolutePath) + arguments)
        .redirectErrorStream(true)
        .start()
    val value = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() == 0) value.takeIf { it.isNotBlank() } else null
} catch (_: Exception) {
    null
}

private fun requiredBuildMetadataPart(name: String, value: String): String {
    require(value.isNotBlank() && value.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) {
        "$name must be a non-blank value without whitespace or control characters; was '$value'."
    }
    return value
}

private fun kotlinStringLiteral(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("$", "\\$")

// Commit metadata remains stable across no-op builds, including builds in a later hour.
// Source archives without Git use deterministic defaults; CI can supply explicit properties.
val deadlinerCoreCommit = requiredBuildMetadataPart(
    "deadlinerCoreCommit",
    providers.gradleProperty("deadlinerCoreCommit")
        .orElse(gitMetadata(rootProject.projectDir, "rev-parse", "HEAD") ?: "unknown")
        .get(),
)
val deadlinerCoreCompiledAtUtc = providers.gradleProperty("deadlinerCoreCompiledAtUtc")
    .orElse(gitMetadata(rootProject.projectDir, "show", "-s", "--format=%cI", "HEAD") ?: Instant.EPOCH.toString())
    .get()
    .let { Instant.parse(it).toString() }
private val coreCompileDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HH").withZone(ZoneOffset.UTC)
val deadlinerCoreCompileDate = providers.gradleProperty("deadlinerCoreCompileDate")
    .orElse(coreCompileDateFormat.format(Instant.parse(deadlinerCoreCompiledAtUtc)))
    .get()
    .also {
        require(Regex("""\d{8}-(?:[01]\d|2[0-3])""").matches(it)) {
            "deadlinerCoreCompileDate must use YYYYMMDD-HH; was '$it'."
        }
    }
val deadlinerCoreCompileBranch = requiredBuildMetadataPart(
    "deadlinerCoreCompileBranch",
    providers.gradleProperty("deadlinerCoreCompileBranch")
        .orElse(gitMetadata(rootProject.projectDir, "branch", "--show-current") ?: "detached")
        .get(),
)
val deadlinerCoreCompileUserName = requiredBuildMetadataPart(
    "deadlinerCoreCompileUserName",
    providers.gradleProperty("deadlinerCoreCompileUserName")
        .orElse(System.getProperty("user.name") ?: "unknown")
        .get(),
)
val deadlinerCoreVersion = "DeadlinerCore-${deadlinerCoreCommit.take(6)}-$deadlinerCoreCompileBranch@$deadlinerCoreCompileUserName"
val deadlinerCoreBridgeVersion = providers.gradleProperty("deadlinerCoreBridgeVersion")
    .orElse("15")
    .get()
    .toIntOrNull()
    ?.takeIf { it >= 0 }
    ?: throw GradleException("deadlinerCoreBridgeVersion must be a non-negative integer.")

val kmpBuildMetadataDirectory = layout.buildDirectory.dir("generated/kmp-build-metadata/commonMain")
val generateKmpBuildMetadata by tasks.registering {
    outputs.dir(kmpBuildMetadataDirectory)
    inputs.property("deadlinerCoreVersion", deadlinerCoreVersion)
    inputs.property("deadlinerCoreCompileDate", deadlinerCoreCompileDate)
    inputs.property("deadlinerCoreCompileBranch", deadlinerCoreCompileBranch)
    inputs.property("deadlinerCoreCompileUserName", deadlinerCoreCompileUserName)
    inputs.property("deadlinerCoreBridgeVersion", deadlinerCoreBridgeVersion)
    inputs.property("deadlinerCoreCommit", deadlinerCoreCommit)
    inputs.property("deadlinerCoreCompiledAtUtc", deadlinerCoreCompiledAtUtc)
    doLast {
        val output = kmpBuildMetadataDirectory.get().file("com/deadliner/platform/KmpBuildMetadata.kt").asFile
        output.parentFile.mkdirs()
        val content =
                "package com.deadliner.platform\n\n" +
                "object KmpBuildMetadata {\n" +
                "    const val coreVersion: String = \"${kotlinStringLiteral(deadlinerCoreVersion)}\"\n" +
                "    const val compileDate: String = \"${kotlinStringLiteral(deadlinerCoreCompileDate)}\"\n" +
                "    const val compileBranch: String = \"${kotlinStringLiteral(deadlinerCoreCompileBranch)}\"\n" +
                "    const val compileUserName: String = \"${kotlinStringLiteral(deadlinerCoreCompileUserName)}\"\n" +
                "    const val bridgeVersion: Int = $deadlinerCoreBridgeVersion\n" +
                "    const val compiledAtUtc: String = \"${kotlinStringLiteral(deadlinerCoreCompiledAtUtc)}\"\n" +
                "}\n"
        if (!output.isFile || output.readText() != content) {
            output.writeText(content)
        }
    }
}

tasks.register("printDeadlinerCoreVersion") {
    group = "help"
    description = "Prints the KMP core build identifier and the components used to generate it."
    doLast {
        println("deadlinerCoreVersion=$deadlinerCoreVersion")
        println("deadlinerCoreCommit=$deadlinerCoreCommit")
        println("deadlinerCoreCompiledAtUtc=$deadlinerCoreCompiledAtUtc")
        println("deadlinerCoreCompileDate=$deadlinerCoreCompileDate")
        println("deadlinerCoreCompileBranch=$deadlinerCoreCompileBranch")
        println("deadlinerCoreCompileUserName=$deadlinerCoreCompileUserName")
        println("deadlinerCoreBridgeVersion=$deadlinerCoreBridgeVersion")
    }
}

tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin")) {
        dependsOn(generateKmpBuildMetadata)
    }
}

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "com.deadliner.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
}

// Optional source overlay: a public checkout never fetches private repositories.
val lifiMode = providers.gradleProperty("lifiMode").orElse("auto").get()
require(lifiMode in setOf("auto", "stub", "private")) { "lifiMode must be auto, stub or private" }
val lifiDirectory = rootProject.file(".private/lifi")
val lifiInstalled = lifiDirectory.resolve("lifi-api-version").isFile
val usePrivateLifi = lifiMode == "private" || (lifiMode == "auto" && lifiInstalled)
if (usePrivateLifi) {
    require(lifiInstalled && lifiDirectory.resolve("lifi-api-version").readText().trim() == "1") {
        "Lifi contract version 1 required. Run runscript/setupLifi.sh, or use -PlifiMode=stub."
    }
    require(lifiDirectory.resolve("src/commonMain/kotlin/com/deadliner/interfaces/LifiRuntimeFactory.kt").isFile) {
        "Incomplete private Lifi checkout. Run runscript/setupLifi.sh."
    }
}
logger.lifecycle("Lifi implementation: ${if (usePrivateLifi) "private" else "stub"}")

kotlin {
    androidTarget {
        compilations.configureEach {
            if (name.contains("debug", ignoreCase = true)) {
                compileTaskProvider.configure {
                    // Preserve coroutine locals for Android Studio's Kotlin debugger.
                    compilerOptions.freeCompilerArgs.add("-Xdebug")
                }
            }
        }
    }
    // Keep the outer XCFramework name aligned with the inner framework
    // baseName. Xcode rejects a lowercase `shared.xcframework` containing
    // `Shared.framework` when reassembling a release archive.
    val xcf = XCFramework("Shared")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            xcf.add(this)
        }
    }
    macosArm64 {
        // Diagnostic arguments are opt-in; normal builds keep the toolchain defaults.
        binaries.all {
            linkTaskProvider.configure {
                toolOptions.freeCompilerArgs.addAll(
                    providers.gradleProperty("nativeStabilityDiagnosticArgs")
                        .map { it.split(' ').filter(String::isNotBlank) }
                        .orElse(emptyList()),
                )
            }
        }
    }
    val ohosSqliteStaticLibrariesDir = layout.buildDirectory.dir("intermediates/ohos-sqlite-static-libraries")
    val extractOhosSqliteStaticLibraries by tasks.registering(Copy::class) {
        from(providers.provider {
                configurations.getByName("ohosArm64CInterop").files
                    .filter { it.name.startsWith("sqliter-driver-ohosArm64Cinterop") }
                    .map(::zipTree)
        }) {
            include("default/targets/ohos_arm64/included/*.a")
            eachFile { path = name }
            includeEmptyDirs = false
        }
        into(ohosSqliteStaticLibrariesDir)
    }

    ohosArm64 {
        val nativeSysroot = providers.gradleProperty("harmonyNativeSysroot")
            .orElse(providers.environmentVariable("DEVECO_SDK_HOME").map {
                "$it/default/openharmony/native/sysroot"
            })
            .orElse("/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/sysroot")
        compilations.getByName("main").cinterops.create("mindspore") {
            defFile(project.file("src/nativeInterop/cinterop/mindspore.def"))
            compilerOpts("-I${nativeSysroot.get()}/usr/include")
        }
        binaries.sharedLib {
            baseName = "DeadlinerCore"
            if (buildType == NativeBuildType.DEBUG) {
                // CPF's unoptimized OHOS runtime retains unavailable ObjC/GC
                // diagnostic imports. Optimize dead runtime paths while retaining
                // full DWARF and the original Kotlin source paths for breakpoints.
                optimized = true
                debuggable = true
            }
            // CPF SQLiter ships SQLite as archives inside its CInterop KLIB. Extract
            // them into the build directory so the OHOS linker can resolve -lsqlite3.
            linkerOpts(
                "-L${ohosSqliteStaticLibrariesDir.get().asFile.absolutePath}",
                "-lsqlite3",
                "-lz",
                "-lclang_rt.builtins",
                // Kotlin/Native's OHOS shared runtime records a relocation for `main`.
                // NAPI libraries have no executable entry point, so alias it to a stable bridge
                // export at link time instead of relying on the host ArkTS process.
                "--defsym=main=deadliner_persistence_bridge_version",
                "-L${nativeSysroot.get()}/usr/lib/aarch64-linux-ohos",
                "-lmindspore_lite_ndk",
            )
            val linkedLibrary = outputFile
            linkTaskProvider.configure {
                dependsOn(extractOhosSqliteStaticLibraries)
                doLast {
                    val nm = File(nativeSysroot.get()).parentFile.resolve("llvm/bin/llvm-nm")
                    check(nm.isFile) { "OHOS llvm-nm missing at $nm; configure the DevEco Native SDK." }
                    val process = ProcessBuilder(nm.absolutePath, "--dynamic", "--undefined-only", linkedLibrary.absolutePath)
                        .redirectErrorStream(true).start()
                    val symbols = process.inputStream.bufferedReader().use { it.readText() }
                    check(process.waitFor() == 0) { "Cannot verify OHOS runtime imports: $symbols" }
                    val missing = symbols.lineSequence().map { it.trim().substringAfterLast(' ') }
                        .filter { it.startsWith("Kotlin_") || it.startsWith("Konan_") }.toList()
                    check(missing.isEmpty()) { "OHOS library cannot load: unresolved Kotlin runtime symbols: $missing" }
                }
            }
        }
    }
    sourceSets {
        // Private code compiles against Core's public contracts in the same KMP
        // compilation. Source roots are mutually exclusive, including test roots.
        configureEach {
            if (usePrivateLifi) {
                kotlin.srcDir(lifiDirectory.resolve("src/$name/kotlin"))
                resources.srcDir(lifiDirectory.resolve("src/$name/resources"))
            }
        }
        if (!usePrivateLifi) {
            named("commonMain") { kotlin.srcDir("src/lifiStub/kotlin") }
            named("commonTest") { kotlin.srcDir("src/lifiStubTest/kotlin") }
        }
        named("commonMain") {
            kotlin.srcDir(kmpBuildMetadataDirectory)
        }
        // The model cache uses the same real POSIX file implementation on Native targets.
        configureEach {
            if (name in setOf("iosMain", "macosArm64Main", "ohosArm64Main")) {
                kotlin.srcDir("src/posixMain/kotlin")
            }
        }
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            // WebDAV protocol code is shared. Platform source sets contribute
            // only the Ktor engine (Darwin, OkHttp, Curl).
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            // Host apps explicitly opt in to packaging the native runtime.
            compileOnly(libs.onnxruntime.android)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.androidx.core)
            implementation(libs.androidx.work.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.darwin)
        }
        macosArm64Main.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.darwin)
        }
        val ohosArm64Main by getting {
            dependencies {
                implementation(libs.sqldelight.native.driver)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.curl)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
        }
        macosArm64Test.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Preserve the original binary, test arguments, filters and link dependency.
// Merge process streams instead of racing KGP's two readers into TestOutputStore.
tasks.named<KotlinNativeTest>("macosArm64Test") {
    dependsOn("linkDebugTestMacosArm64")
    val nativeTestBinary = layout.buildDirectory.file("bin/macosArm64/debugTest/test.kexe")
    environment("DEADLINER_NATIVE_TEST_EXECUTABLE", nativeTestBinary.get().asFile.absolutePath)
    executableProperty.set(files(rootProject.file("runscript/native/run-test-stream.sh")))
    inputs.file(nativeTestBinary).withPropertyName("nativeTestBinary")
    inputs.file(rootProject.file("runscript/native/run-test-stream.sh"))
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.deadliner.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/com/deadliner/db/migrations"))
            verifyMigrations.set(true)
        }
    }
}

val harmonyEntryDirectory = rootProject.projectDir.resolve("../../HarmonyOS/Deadliner/entry")

tasks.register<Copy>("publishDebugOhosLibraryToDeadliner") {
    dependsOn("linkDebugSharedOhosArm64")
    from(layout.buildDirectory.file("bin/ohosArm64/debugShared/libDeadlinerCore.so"))
    into(harmonyEntryDirectory.resolve("libs/arm64-v8a"))
}

tasks.register<Copy>("publishReleaseOhosLibraryToDeadliner") {
    dependsOn("linkReleaseSharedOhosArm64")
    from(layout.buildDirectory.file("bin/ohosArm64/releaseShared/libDeadlinerCore.so"))
    into(harmonyEntryDirectory.resolve("libs/arm64-v8a"))
}
