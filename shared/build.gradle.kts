import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The core build identifier is embedded in every KMP artifact as:
 * `DeadlinerCore-<compileDate>-<compileBranch>@<compileUserName>`.
 *
 * Release automation can override any generated component with a Gradle property, which keeps
 * CI artifacts reproducible while local builds retain useful origin information.
 */
private fun currentGitBranch(projectDirectory: File): String = try {
    val process = ProcessBuilder("git", "-C", projectDirectory.absolutePath, "branch", "--show-current")
        .redirectErrorStream(true)
        .start()
    val branch = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() == 0 && branch.isNotBlank()) branch else "detached"
} catch (_: Exception) {
    "unknown"
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

private val coreCompileDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HH").withZone(ZoneOffset.UTC)
val deadlinerCoreCompileDate = providers.gradleProperty("deadlinerCoreCompileDate")
    .orElse(coreCompileDateFormat.format(Instant.now()))
    .get()
    .also {
        require(Regex("""\d{8}-(?:[01]\d|2[0-3])""").matches(it)) {
            "deadlinerCoreCompileDate must use YYYYMMDD-HH; was '$it'."
        }
    }
val deadlinerCoreCompileBranch = requiredBuildMetadataPart(
    "deadlinerCoreCompileBranch",
    providers.gradleProperty("deadlinerCoreCompileBranch")
        .orElse(currentGitBranch(rootProject.projectDir))
        .get(),
)
val deadlinerCoreCompileUserName = requiredBuildMetadataPart(
    "deadlinerCoreCompileUserName",
    providers.gradleProperty("deadlinerCoreCompileUserName")
        .orElse(System.getProperty("user.name") ?: "unknown")
        .get(),
)
val deadlinerCoreVersion = "DeadlinerCore-$deadlinerCoreCompileDate-$deadlinerCoreCompileBranch@$deadlinerCoreCompileUserName"
val deadlinerCoreBridgeVersion = providers.gradleProperty("deadlinerCoreBridgeVersion")
    .orElse("12")
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
    outputs.upToDateWhen { false }
    doLast {
        val output = kmpBuildMetadataDirectory.get().file("com/deadliner/platform/KmpBuildMetadata.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(
                "package com.deadliner.platform\n\n" +
                "object KmpBuildMetadata {\n" +
                "    const val coreVersion: String = \"${kotlinStringLiteral(deadlinerCoreVersion)}\"\n" +
                "    const val compileDate: String = \"${kotlinStringLiteral(deadlinerCoreCompileDate)}\"\n" +
                "    const val compileBranch: String = \"${kotlinStringLiteral(deadlinerCoreCompileBranch)}\"\n" +
                "    const val compileUserName: String = \"${kotlinStringLiteral(deadlinerCoreCompileUserName)}\"\n" +
                "    const val bridgeVersion: Int = $deadlinerCoreBridgeVersion\n" +
                "    const val compiledAtUtc: String = \"" + Instant.now().toString() + "\"\n" +
                "}\n",
        )
    }
}

tasks.register("printDeadlinerCoreVersion") {
    group = "help"
    description = "Prints the KMP core build identifier and the components used to generate it."
    doLast {
        println("deadlinerCoreVersion=$deadlinerCoreVersion")
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
    }
}

kotlin {
    androidTarget()
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
    macosArm64()
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
        binaries.sharedLib {
            baseName = "DeadlinerCore"
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
            )
            linkTaskProvider.configure {
                dependsOn(extractOhosSqliteStaticLibraries)
            }
        }
    }
    sourceSets {
        named("commonMain") {
            kotlin.srcDir(kmpBuildMetadataDirectory)
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
    // The Kotlin/Native debug runtime leaves symbols unresolved in an ArkTS
    // process (including Kotlin_Interop_CreateObjCObjectHolder).  The host
    // application may itself be a debug HAP, but it must package the fully
    // linked release KMP shared library.
    dependsOn("linkReleaseSharedOhosArm64")
    from(layout.buildDirectory.file("bin/ohosArm64/releaseShared/libDeadlinerCore.so"))
    into(harmonyEntryDirectory.resolve("libs/arm64-v8a"))
}

tasks.register<Copy>("publishReleaseOhosLibraryToDeadliner") {
    dependsOn("linkReleaseSharedOhosArm64")
    from(layout.buildDirectory.file("bin/ohosArm64/releaseShared/libDeadlinerCore.so"))
    into(harmonyEntryDirectory.resolve("libs/arm64-v8a"))
}
