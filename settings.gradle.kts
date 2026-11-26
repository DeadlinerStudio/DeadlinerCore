pluginManagement {
    repositories {
        // CPF fork artifacts must win over public Kotlin/SQLDelight artifacts.
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.jetbrains\\.kotlinx(\\..*)?")
                includeGroupByRegex("app\\.cash\\.sqldelight(\\..*)?")
                includeGroupByRegex("co\\.touchlab(\\..*)?")
                includeGroupByRegex("io\\.ktor(\\..*)?")
                includeGroupByRegex("com\\.squareup\\.okio(\\..*)?")
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.jetbrains\\.kotlinx(\\..*)?")
                includeGroupByRegex("app\\.cash\\.sqldelight(\\..*)?")
                includeGroupByRegex("co\\.touchlab(\\..*)?")
                includeGroupByRegex("io\\.ktor(\\..*)?")
                includeGroupByRegex("com\\.squareup\\.okio(\\..*)?")
            }
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // CPF supplies HarmonyOS-compatible fork variants; Aliyun accelerates
        // transitively resolved Google and Maven Central artifacts in China.
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.jetbrains\\.kotlinx(\\..*)?")
                includeGroupByRegex("app\\.cash\\.sqldelight(\\..*)?")
                includeGroupByRegex("co\\.touchlab(\\..*)?")
                includeGroupByRegex("io\\.ktor(\\..*)?")
                includeGroupByRegex("com\\.squareup\\.okio(\\..*)?")
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.jetbrains\\.kotlinx(\\..*)?")
                includeGroupByRegex("app\\.cash\\.sqldelight(\\..*)?")
                includeGroupByRegex("co\\.touchlab(\\..*)?")
                includeGroupByRegex("io\\.ktor(\\..*)?")
                includeGroupByRegex("com\\.squareup\\.okio(\\..*)?")
            }
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "deadliner-kmp"
include(":shared")

// Set Native home before KGP applies: its Native property service memoizes early.
// The overlay repairs one fingerprint-guarded CPF exception-frame codegen defect.
val nativeRootFixScript = File(settingsDir, "runscript/native/shadow-stack-toolchain.gradle.kts")
val nativeRootFixVersion = Regex("(?m)^kotlin\\s*=\\s*\"([^\"]+)\"")
    .find(File(settingsDir, "gradle/libs.versions.toml").readText())!!.groupValues[1]
gradle.beforeProject {
    if (path == ":shared") {
        extra["deadlinerNativeToolchainVersion"] = nativeRootFixVersion
        apply(from = nativeRootFixScript)
    }
}
