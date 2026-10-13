@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.deadliner.domain.quickadd.model

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun createQuickAddModelFileStore(directory: String): QuickAddModelFileStore = PosixModelFileStore(directory)
private class PosixModelFileStore(private val root: String) : QuickAddModelFileStore {
    init {
        require(root.startsWith('/')) { "Model cache directory must be absolute" }
        check(mkdir(root, 0x1c0.convert()) == 0 || errno == EEXIST) { "Cannot create model cache directory" }
    }
    override fun path(name: String): String {
        require(Regex("[A-Za-z0-9._-]+").matches(name) && name != "." && name != "..")
        return root.trimEnd('/') + "/" + name
    }
    override fun exists(name: String) = access(path(name), F_OK) == 0
    override fun reader(name: String): QuickAddModelReader = PosixModelReader(fopen(path(name), "rb") ?: error("Cannot open cached model"))
    override fun writer(name: String): QuickAddModelWriter = PosixModelWriter(fopen(path(name), "wb") ?: error("Cannot create model download"))
    override fun promote(temporary: String, destination: String) { check(rename(path(temporary), path(destination)) == 0) { "Cannot commit model cache" } }
    override fun delete(name: String) { check(remove(path(name)) == 0 || errno == ENOENT) { "Cannot remove model staging file" } }
}
private class PosixModelReader(private val file: CPointer<FILE>) : QuickAddModelReader {
    override fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty())
        val count = buffer.usePinned { fread(it.addressOf(0), 1u, buffer.size.convert(), file).toInt() }
        check(ferror(file) == 0) { "Cached model read failed" }
        return if (count == 0) -1 else count
    }
    override fun close() { check(fclose(file) == 0) { "Cached model close failed" } }
}
private class PosixModelWriter(private val file: CPointer<FILE>) : QuickAddModelWriter {
    override fun write(buffer: ByteArray, count: Int) {
        require(count in 1..buffer.size)
        buffer.usePinned { check(fwrite(it.addressOf(0), 1u, count.convert(), file).toInt() == count) { "Model write failed" } }
    }
    override fun close() {
        try { check(fflush(file) == 0 && fsync(fileno(file)) == 0) { "Model flush failed" } }
        finally { check(fclose(file) == 0) { "Model file close failed" } }
    }
}
