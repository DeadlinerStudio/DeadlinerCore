@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.deadliner.domain.quickadd.model

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

class NativeModelCacheTest {
    @Test fun realFilesCommitReplaceAndReadWithoutLeavingStagingFiles() {
        val directory = memScoped {
            val bytes = "/tmp/deadliner-model-cache-XXXXXX".encodeToByteArray()
            val pointer = allocArray<ByteVar>(bytes.size + 1)
            bytes.forEachIndexed { index, value -> pointer[index] = value }
            pointer[bytes.size] = 0
            checkNotNull(mkdtemp(pointer)).toKString()
        }
        val files = createQuickAddModelFileStore(directory)
        try {
            for (text in listOf("abc", "replacement")) {
                val bytes = text.encodeToByteArray()
                val writer = files.writer("model.part")
                try { writer.write(bytes, bytes.size) } finally { writer.close() }
                files.promote("model.part", "model.onnx")
                assertFalse(files.exists("model.part"))
                val reader = files.reader("model.onnx")
                try {
                    val buffer = ByteArray(32)
                    val count = reader.read(buffer)
                    assertEquals(text, buffer.copyOf(count).decodeToString())
                    assertEquals(-1, reader.read(buffer))
                } finally { reader.close() }
            }
            files.delete("missing.part")
        } finally { files.delete("model.onnx"); rmdir(directory) }
    }
}
