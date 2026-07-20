package com.deadliner.platform

import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.NSFileAttributeKey
import platform.Foundation.NSFileSize

/**
 * iOS FileStorage actual 实现。
 *
 * 使用 NSFileManager 实现文件读写。
 */
actual class FileStorage {
    private val fileManager = NSFileManager.defaultManager

    actual fun save(key: String, data: ByteArray) {
        val path = getDocumentPath(key)
        val nsData = data.toNSData()
        nsData.writeToFile(path, true)
    }

    actual fun load(key: String): ByteArray? {
        val path = getDocumentPath(key)
        val nsData = NSData.dataWithContentsOfFile(path) ?: return null
        return nsData.toByteArray()
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

// ===== NSData 与 ByteArray 转换 =====

fun ByteArray.toNSData(): NSData {
    return this.usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), this.size.toULong())
    }
}

fun NSData.toByteArray(): ByteArray {
    return ByteArray(this.length.toInt()).apply {
        usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
        }
    }
}
