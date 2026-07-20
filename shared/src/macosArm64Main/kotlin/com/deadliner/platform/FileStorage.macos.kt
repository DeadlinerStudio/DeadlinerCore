package com.deadliner.platform

/** The macOS target has no product-host storage contract; callers must inject a host implementation. */
actual class FileStorage {
    actual fun save(key: String, data: ByteArray): Unit = unsupported()
    actual fun load(key: String): ByteArray? = unsupported()
    actual fun delete(key: String): Unit = unsupported()
    actual fun exists(key: String): Boolean = unsupported()
    actual fun getDocumentDirectory(): String = unsupported()
    actual fun getTempDirectory(): String = unsupported()
    actual fun clearTemp(): Unit = unsupported()
    actual fun getFileSize(key: String): Long? = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "FileStorage is unavailable in the macOS development target.",
    )
}
