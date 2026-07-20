package com.deadliner.platform

import android.content.Context
import java.io.File

/**
 * Android FileStorage actual 实现。
 *
 * 使用 Context.filesDir 实现文件读写。
 */
actual class FileStorage(private val context: Context) {
    actual fun save(key: String, data: ByteArray) {
        val file = getDocumentFile(key)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }

    actual fun load(key: String): ByteArray? {
        val file = getDocumentFile(key)
        return if (file.exists()) {
            file.readBytes()
        } else {
            null
        }
    }

    actual fun delete(key: String) {
        val file = getDocumentFile(key)
        if (file.exists()) {
            file.delete()
        }
    }

    actual fun exists(key: String): Boolean {
        return getDocumentFile(key).exists()
    }

    actual fun getDocumentDirectory(): String {
        return context.filesDir.absolutePath
    }

    actual fun getTempDirectory(): String {
        return context.cacheDir.absolutePath
    }

    actual fun clearTemp() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            } else if (file.isDirectory) {
                file.deleteRecursively()
            }
        }
    }

    actual fun getFileSize(key: String): Long? {
        val file = getDocumentFile(key)
        return if (file.exists()) {
            file.length()
        } else {
            null
        }
    }

    // ===== 辅助方法 =====

    private fun getDocumentFile(key: String): File {
        return File(context.filesDir, key)
    }
}
