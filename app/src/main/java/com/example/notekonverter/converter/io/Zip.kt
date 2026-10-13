package com.example.notekonverter.converter.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Reads every file entry of a ZIP archive into memory, keyed by entry name. */
fun readZip(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
        while (true) {
            val e = zin.nextEntry ?: break
            if (!e.isDirectory) out[e.name] = zin.readBytes()
        }
    }
    return out
}

/**
 * Builds a ZIP with STORED entries. Stored entries carry their CRC and sizes in
 * the local header, so no data descriptors are emitted; that keeps the archive
 * readable by the minimal native ZIP readers in the vendor apps.
 */
class ZipBuilder {
    private val buffer = ByteArrayOutputStream()
    private val zip = ZipOutputStream(buffer)

    fun dir(name: String) = apply {
        val e = ZipEntry(if (name.endsWith("/")) name else "$name/")
        e.method = ZipEntry.STORED
        e.size = 0
        e.compressedSize = 0
        e.crc = 0
        zip.putNextEntry(e)
        zip.closeEntry()
    }

    fun file(name: String, data: ByteArray) = apply {
        val crc = CRC32().apply { update(data) }
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = data.size.toLong()
        e.compressedSize = data.size.toLong()
        e.crc = crc.value
        zip.putNextEntry(e)
        zip.write(data)
        zip.closeEntry()
    }

    fun build(): ByteArray {
        zip.finish()
        zip.close()
        return buffer.toByteArray()
    }
}
