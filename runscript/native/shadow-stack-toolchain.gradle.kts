import java.io.File

// KGP reads extra properties lazily when resolving its Native toolchain provider.
// This changes Native code generation only, never Android/JVM or business interfaces.
val cpfVersion = project.extra["deadlinerNativeToolchainVersion"].toString()
if (cpfVersion == "2.2.21-0.3.0") {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val host = when {
        osName.contains("mac") -> if (arch == "aarch64" || arch == "arm64") "macos-aarch64" else "macos-x86_64"
        osName.contains("linux") -> "linux-x86_64"
        osName.contains("windows") -> "windows-x86_64"
        else -> error("Unsupported Native hotfix host: $osName/$arch")
    }
    val konanData = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    val original = providers.gradleProperty("deadlinerNativeOriginalHome").orNull?.let(::File)
        ?: File(konanData, "kotlin-native-prebuilt-$host-$cpfVersion")
    val sourceJar = File(original, "konan/lib/kotlin-native-compiler-embeddable.jar")
    if (sourceJar.isFile) {
        val overlay = rootProject.layout.buildDirectory.dir("native-toolchains/cpf-$cpfVersion-shadow-stack-v1").get().asFile
        val javaExe = File(System.getProperty("java.home"), "bin/" + if (osName.contains("windows")) "java.exe" else "java")
        val source = rootProject.file("runscript/native/NativeShadowStackPatch.java")
        val result = providers.exec {
            commandLine(javaExe.absolutePath, "-cp", sourceJar.absolutePath, source.absolutePath,
                original.absolutePath, overlay.absolutePath)
        }.result.get()
        result.assertNormalExitValue()
        project.extra["kotlin.native.home"] = overlay.absolutePath
    } else {
        val onlyBootstrap = gradle.startParameter.taskNames.all { it.endsWith("downloadKotlinNativeDistribution") }
        val nativeRequested = gradle.startParameter.taskNames.any {
            it.contains("macos", true) || it.contains("ios", true) || it.contains("ohos", true) ||
                it.contains("XCFramework", true) || it.substringAfterLast(':') in setOf("build", "allTests", "check", "assemble")
        }
        check(onlyBootstrap || !nativeRequested) {
            "Install CPF Native first: ./gradlew :shared:downloadKotlinNativeDistribution, then rerun. Original home: $original"
        }
    }
}
