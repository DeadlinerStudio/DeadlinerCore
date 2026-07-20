@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.deadliner.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSFileAttributeKey
import platform.Foundation.NSFileSize
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rewind

/**
 * iOS FileStorage actual 实现。
 *
 * 使用 NSFileManager 实现文件读写。
 */
actual class FileStorage {
    private val fileManager = NSFileManager.defaultManager

    actual fun save(key: String, data: ByteArray) {
        val path = getDocumentPath(key)
        val file = fopen(path, "wb") ?: error("Unable to open $path for writing")
        try {
            if (data.isNotEmpty()) {
                data.usePinned { pinned ->
                    check(fwrite(pinned.addressOf(0), 1u, data.size.toULong(), file) == data.size.toULong()) {
                        "Unable to write $path"
                    }
                }
            }
        } finally {
            fclose(file)
        }
    }

    actual fun load(key: String): ByteArray? {
        val path = getDocumentPath(key)
        val file = fopen(path, "rb") ?: return null
        try {
            check(fseek(file, 0, SEEK_END) == 0) { "Unable to seek $path" }
            val size = ftell(file)
            check(size >= 0) { "Unable to determine size for $path" }
            rewind(file)
            return ByteArray(size.toInt()).also { data ->
                if (data.isNotEmpty()) {
                    data.usePinned { pinned ->
                        check(fread(pinned.addressOf(0), 1u, data.size.toULong(), file) == data.size.toULong()) {
                            "Unable to read $path"
                        }
                    }
                }
            }
        } finally {
            fclose(file)
        }
    }

    actual fun delete(key: String) {
        val path = getDocumentPath(key)
        fileManager.removeItemAtPath(path, null)
    }

    actual fun exists(key: String): Boolean {
        val path = getDocumentPath(key)
        return fileManager.fileExistsAtPath(path)
    }

    actual fun getDocumentDirectory(): String {
        val urls = fileManager.URLsForDirectory(NSDocumentDirectory, platform.Foundation.NSUserDomainMask)
        return (urls.firstOrNull() as? NSURL)?.path ?: ""
    }

    actual fun getTempDirectory(): String {
        return platform.Foundation.NSTemporaryDirectory()
    }

    actual fun clearTemp() {
        val tempDir = getTempDirectory()
        val contents = fileManager.contentsOfDirectoryAtPath(tempDir, null)
        contents?.forEach { item ->
            val path = "$tempDir/$item"
            fileManager.removeItemAtPath(path, null)
        }
    }

    actual fun getFileSize(key: String): Long? {
        val path = getDocumentPath(key)
        val attributes = fileManager.attributesOfItemAtPath(path, null)
        return (attributes?.get(NSFileSize) as? platform.Foundation.NSNumber)?.longValue
    }

    // ===== 辅助方法 =====

    private fun getDocumentPath(key: String): String {
        return "${getDocumentDirectory()}/$key"
    }
}
