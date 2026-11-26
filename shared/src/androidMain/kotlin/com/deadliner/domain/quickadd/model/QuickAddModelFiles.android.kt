package com.deadliner.domain.quickadd.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual fun createQuickAddModelFileStore(directory: String): QuickAddModelFileStore = AndroidModelFileStore(directory)
internal actual fun prepareQuickAddModelFile(path: String, format: QuickAddModelFormat): String {
    require(format == QuickAddModelFormat.ONNX) { "Android requires an ONNX artifact" }
    return path
}
private class AndroidModelFileStore(directory: String) : QuickAddModelFileStore {
    private val root = File(directory).also { require(it.isAbsolute); check(it.isDirectory || it.mkdirs()) }
    private fun file(name: String): File {
        require(Regex("[A-Za-z0-9._-]+").matches(name) && name != "." && name != "..")
        return File(root, name)
    }
    override fun path(name: String) = file(name).absolutePath
    override fun exists(name: String) = file(name).isFile
    override fun reader(name: String): QuickAddModelReader {
        val stream = FileInputStream(file(name))
        return object : QuickAddModelReader {
            override fun read(buffer: ByteArray) = stream.read(buffer)
            override fun close() = stream.close()
        }
    }
    override fun writer(name: String): QuickAddModelWriter {
        val stream = FileOutputStream(file(name))
        return object : QuickAddModelWriter {
            override fun write(buffer: ByteArray, count: Int) = stream.write(buffer, 0, count)
            override fun close() { try { stream.fd.sync() } finally { stream.close() } }
        }
    }
    override fun promote(temporary: String, destination: String) {
        Files.move(file(temporary).toPath(), file(destination).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    override fun delete(name: String) { Files.deleteIfExists(file(name).toPath()) }
}
