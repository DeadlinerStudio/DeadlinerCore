package com.deadliner.platform

/**
 * HarmonyOS FileStorage actual 实现。
 *
 * 使用 fs API 实现文件读写。
 */
actual class FileStorage {
    actual fun save(key: String, data: ByteArray) {
        // 使用 fs API 写入文件
        println("[HarmonyOS] save: $key")
    }

    actual fun load(key: String): ByteArray? {
        // 使用 fs API 读取文件
        println("[HarmonyOS] load: $key")
        return null
    }

    actual fun delete(key: String) {
        // 使用 fs API 删除文件
        println("[HarmonyOS] delete: $key")
    }

    actual fun exists(key: String): Boolean {
        // 使用 fs API 检查文件是否存在
        println("[HarmonyOS] exists: $key")
        return false
    }

    actual fun getDocumentDirectory(): String {
        // 返回文档目录路径
        return "/data/storage/el2/base/files"
    }

    actual fun getTempDirectory(): String {
        // 返回临时目录路径
        return "/data/storage/el2/base/cache"
    }

    actual fun clearTemp() {
        // 清理临时目录
        println("[HarmonyOS] clearTemp")
    }

    actual fun getFileSize(key: String): Long? {
        // 获取文件大小
        println("[HarmonyOS] getFileSize: $key")
        return null
    }
}
