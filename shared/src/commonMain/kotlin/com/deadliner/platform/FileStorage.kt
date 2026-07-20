package com.deadliner.platform

/**
 * 平台文件存储抽象。
 *
 * 各平台 actual 实现：
 * - iOS: FileManager
 * - Android: Context.filesDir
 * - HarmonyOS: fs API
 */
expect class FileStorage {
    /** 保存数据到文件 */
    fun save(key: String, data: ByteArray)

    /** 从文件加载数据 */
    fun load(key: String): ByteArray?

    /** 删除文件 */
    fun delete(key: String)

    /** 检查文件是否存在 */
    fun exists(key: String): Boolean

    /** 获取文档目录路径 */
    fun getDocumentDirectory(): String

    /** 获取临时目录路径 */
    fun getTempDirectory(): String

    /** 清理临时目录 */
    fun clearTemp()

    /** 获取文件大小（字节） */
    fun getFileSize(key: String): Long?
}

/** 文件路径配置（易于扩展） */
data class FilePathConfig(
    val directory: FileDirectory = FileDirectory.DOCUMENT,
    val subdirectory: String? = null,
    val filename: String,
)

/** 文件目录类型（密封类，编译时类型安全） */
enum class FileDirectory {
    DOCUMENT, TEMP, CACHE
}
