@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.deadliner.domain.quickadd.model

import kotlinx.cinterop.*
import platform.CoreML.*
import platform.Foundation.*

/** Download a single-file .mlmodel; compiling on device avoids shipping a platform-specific bundle. */
internal actual fun prepareQuickAddModelFile(path: String, format: QuickAddModelFormat): String = memScoped {
    require(format == QuickAddModelFormat.CORE_ML) { "iOS requires a single-file .mlmodel artifact" }
    val cache = path.removeSuffix(".mlmodel") + ".mlmodelc"
    val files = NSFileManager.defaultManager
    if (files.fileExistsAtPath(cache)) return@memScoped cache
    val failure = alloc<ObjCObjectVar<NSError?>>()
    failure.value = null
    val compiled = MLModel.compileModelAtURL(NSURL.fileURLWithPath(path), failure.ptr)
        ?: error(failure.value?.localizedDescription ?: "Core ML compilation failed")
    try {
        check(files.moveItemAtURL(compiled, NSURL.fileURLWithPath(cache), failure.ptr)) {
            failure.value?.localizedDescription ?: "Cannot cache compiled Core ML model"
        }
    } finally { files.removeItemAtURL(compiled, null) }
    cache
}
