import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.api.tasks.Copy

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
    dependsOn("linkDebugSharedOhosArm64")
    from(layout.buildDirectory.file("bin/ohosArm64/debugShared/libDeadlinerCore.so"))
    into(harmonyEntryDirectory.resolve("libs/arm64-v8a"))
}

tasks.register<Copy>("publishReleaseOhosLibraryToDeadliner") {
    dependsOn("linkReleaseSharedOhosArm64")
    from(layout.buildDirectory.file("bin/ohosArm64/releaseShared/libDeadlinerCore.so"))
    into(harmonyEntryDirectory.resolve("libs/arm64-v8a"))
}
