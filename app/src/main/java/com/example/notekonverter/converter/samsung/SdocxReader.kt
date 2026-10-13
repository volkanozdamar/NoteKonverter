package com.example.notekonverter.converter.samsung

import com.example.notekonverter.converter.InkDocument
import com.example.notekonverter.converter.InkPage
import com.example.notekonverter.converter.InkPoint
import com.example.notekonverter.converter.InkStroke
import com.example.notekonverter.converter.NoteError
import com.example.notekonverter.converter.NoteFormatException
import com.example.notekonverter.converter.PenKind
import com.example.notekonverter.converter.io.ByteSource
import com.example.notekonverter.converter.io.readZip

/**
 * Reads the handwriting out of a Samsung Notes `.sdocx` archive.
 *
 * Layout follows the community reverse-engineering of the WDoc format
 * (github.com/twangodev/sdocx docs, github.com/bxff/samsung-notes-format):
 * a ZIP holding `note.note`, `pageIdInfo.dat` and one `<uuid>.page` per page.
 * Only stroke objects are converted; text boxes, images and shapes are skipped.
 */
object SdocxReader {

    fun read(bytes: ByteArray): InkDocument {
        val files = readZip(bytes)
        val note = files["note.note"] ?: throw NoteFormatException(NoteError.NOT_SAMSUNG)
        val header = parseNoteHeader(note)

        val pageIds = files["pageIdInfo.dat"]?.let(::parsePageOrder)
            ?.takeIf { ids -> ids.all { files.containsKey("$it.page") } }
            ?: files.keys.filter { it.endsWith(".page") && !it.contains('/') }.map { it.removeSuffix(".page") }

        val pages = pageIds.map { id -> parsePage(files.getValue("$id.page"), header.penNames) }
        if (pages.isEmpty()) throw NoteFormatException(NoteError.NO_PAGES)
        return InkDocument(header.title, pages)
    }

    private class NoteHeader(val title: String, val penNames: Map<Int, String>)

    private fun parseNoteHeader(data: ByteArray): NoteHeader {
        val s = ByteSource(data)
        val flexOffset = s.i32()
        s.skip(s.u8()) // property mask
        val fieldMask = maskValue(s.bytes(s.u8()))
        s.skip(4) // format version
        s.utf16() // note id
        s.skip(4 + 8 + 8 + 4 + 4 + 4 + 4 + 4) // revision, times, size, padding, min version
        val titleSize = s.i32()
        val title = runCatching { titleText(data.copyOfRange(s.pos, s.pos + titleSize)) }.getOrNull().orEmpty()
        val penNames = runCatching { parseStringTable(data, flexOffset, fieldMask) }.getOrNull().orEmpty()
        return NoteHeader(title, penNames)
    }

    /** Finds the type-7 shape-text frame of the title object and returns its text. */
    private fun titleText(obj: ByteArray): String {
        var p = 0
        val s = ByteSource(obj)
        while (p + 10 < obj.size) {
            s.pos = p
            val size = s.i32()
            val type = s.i16()
            val flex = s.i32()
            s.skip(s.u8())
            val fieldMask = maskValue(s.bytes(s.u8()))
            if (type == 7 && fieldMask and 1L != 0L) {
                s.pos = p + flex
                s.skip(4) // payload size
                val len = s.i32()
                return String(CharArray(len) { s.u16().toChar() })
            }
            if (size <= 0) break
            p += size
        }
        return ""
    }

    /** Document flexible fields up to bit 10 (string-ID table, which maps pen-name IDs). */
    private fun parseStringTable(data: ByteArray, flexOffset: Int, mask: Long): Map<Int, String> {
        val s = ByteSource(data)
        s.pos = flexOffset
        fun has(bit: Int) = mask and (1L shl bit) != 0L
        // Bits whose layout is not pinned down: give up rather than misread.
        if (has(2) || has(4) || has(5) || has(8)) return emptyMap()
        if (has(0)) s.utf16()
        if (has(1)) { s.skip(8); s.utf16() }
        if (has(3)) s.skip(16)
        if (has(6)) s.utf16()
        if (has(7)) s.skip(4)
        if (has(9)) s.skip(12)
        if (!has(10)) return emptyMap()
        s.skip(4) // byte size
        val count = s.u16()
        return buildMap { repeat(count) { put(s.i32(), s.utf16()) } }
    }

    private fun parsePageOrder(data: ByteArray): List<String> {
        val s = ByteSource(data)
        s.skip(32)
        val count = s.u16()
        return List(count) { s.utf16().also { s.skip(32) } }
    }

    private fun parsePage(data: ByteArray, penNames: Map<Int, String>): InkPage {
        val s = ByteSource(data)
        val layerOffset = s.i32()
        s.skip(4) // flexible offset
        s.skip(s.u8())
        s.skip(s.u8())
        s.skip(4) // orientation
        val width = s.i32().toFloat()
        val height = s.i32().toFloat()

        val strokes = ArrayList<InkStroke>()
        s.pos = layerOffset
        val layerCount = s.u16()
        s.skip(2) // current layer index
        repeat(layerCount) {
            val layerStart = s.pos
            s.pos = layerStart + s.i32() // skip the layer header frame
            val objectCount = s.i32()
            repeat(objectCount) { readObject(s, data, penNames, strokes) }
            s.skip(32) // layer hash
        }
        return InkPage(width, height, strokes)
    }

    private fun readObject(s: ByteSource, data: ByteArray, penNames: Map<Int, String>, out: MutableList<InkStroke>) {
        val type = s.u8()
        val children = s.u16()
        val declared = s.i32()
        val payloadStart = s.pos
        if (type == 1 || type == 15) {
            runCatching { decodeStroke(data, payloadStart, declared - 32, penNames) }.getOrNull()?.let(out::add)
        }
        s.pos = payloadStart + declared
        repeat(children) { readObject(s, data, penNames, out) }
    }

    private fun decodeStroke(data: ByteArray, start: Int, length: Int, penNames: Map<Int, String>): InkStroke? {
        val s = ByteSource(data)
        // Frame 0: common object base. Skip it whole.
        s.pos = start
        val baseSize = s.i32()
        val frame = start + baseSize
        if (baseSize <= 0 || baseSize >= length) return null

        s.pos = frame
        s.skip(4) // frame size
        if (s.i16() != 1) return null
        val flexOffset = s.i32()
        val props = maskValue(s.bytes(s.u8()))
        val fields = maskValue(s.bytes(s.u8()))
        val n = s.u16()
        if (n == 0) return null

        val compressed = props and 0x1L != 0L
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val pressure = FloatArray(n)
        val time = IntArray(n)
        if (compressed) {
            xs[0] = s.f64(); ys[0] = s.f64()
            for (i in 1 until n) {
                xs[i] = xs[i - 1] + signedMagnitude(s.u16()) / 32.0
                ys[i] = ys[i - 1] + signedMagnitude(s.u16()) / 32.0
            }
            pressure[0] = s.f32()
            for (i in 1 until n) pressure[i] = pressure[i - 1] + signedMagnitude(s.u16()) / 4096f
            time[0] = s.i32()
            for (i in 1 until n) time[i] = time[i - 1] + s.u16()
        } else {
            for (i in 0 until n) { xs[i] = s.f64(); ys[i] = s.f64() }
            for (i in 0 until n) pressure[i] = s.f32()
            for (i in 0 until n) time[i] = s.i32()
        }
        // Tilt/orientation channels (props bit 2) follow but are not carried over.

        // Flexible style fields, in mask-bit order.
        s.pos = frame + flexOffset
        fun has(bit: Int) = fields and (1L shl bit) != 0L
        var legacyPen = -1
        var penId = -1
        var color = 0xFF000000.toInt()
        var size = 4f
        if (has(0)) legacyPen = s.i32()
        if (has(1)) s.skip(4)
        if (has(2)) color = s.i32()
        if (has(3)) size = s.f32()
        if (!has(5) && !has(6)) {
            if (has(4)) s.skip(1)
            if (has(7)) penId = s.i32()
        }
        val penName = penNames[if (penId >= 0) penId else legacyPen].orEmpty()
        val kind = if ("Marker" in penName || "Highlighter" in penName) PenKind.HIGHLIGHTER else PenKind.PEN

        val t0 = time[0]
        val points = List(n) { i ->
            InkPoint(xs[i].toFloat(), ys[i].toFloat(), pressure[i].coerceIn(0f, 1f), time[i] - t0)
        }
        return InkStroke(points, color, size, kind)
    }

    /** Sign in bit 15, magnitude in the low 15 bits. */
    private fun signedMagnitude(word: Int): Int {
        val m = word and 0x7FFF
        return if (word and 0x8000 != 0) -m else m
    }

    private fun maskValue(bytes: ByteArray): Long {
        var v = 0L
        for (i in bytes.indices.reversed()) v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        return v
    }
}
