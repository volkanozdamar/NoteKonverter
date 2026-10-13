package com.example.notekonverter.converter.saber

import com.example.notekonverter.converter.InkDocument
import com.example.notekonverter.converter.InkPage
import com.example.notekonverter.converter.InkStroke
import com.example.notekonverter.converter.PenKind
import com.example.notekonverter.converter.io.Bson
import com.example.notekonverter.converter.io.BsonBinary
import com.example.notekonverter.converter.io.ZipBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a Saber archive (`.sba`): a ZIP with the BSON note as `main.sbn2`.
 * Ink-only notes have no assets, so no `main.sbn2.N` entries are written.
 * Saber names the imported note after the `.sba` file, not after anything inside.
 */
object SaberWriter {
    const val PAGE_WIDTH = 1000f
    const val PAGE_HEIGHT = 1400f
    const val DEFAULT_SIZE = 10f

    /** Saber's note format version that introduced `.sba`. */
    private const val FORMAT_VERSION = 19
    private const val HIGHLIGHTER_ALPHA = 100

    fun write(doc: InkDocument): ByteArray = ZipBuilder().file("main.sbn2", sbn2(doc)).build()

    fun sbn2(doc: InkDocument): ByteArray {
        val pages = doc.pages.ifEmpty { listOf(InkPage(PAGE_WIDTH, PAGE_HEIGHT, emptyList())) }
        val root = linkedMapOf<String, Any?>(
            "v" to FORMAT_VERSION,
            "ni" to 0,
            "b" to null,
            "p" to "none",
            "l" to 40,
            "lt" to 3,
            "z" to pages.mapIndexed { index, page ->
                buildMap {
                    put("w", page.width.toDouble())
                    put("h", page.height.toDouble())
                    val strokes = page.strokes.filter { it.points.isNotEmpty() }
                    if (strokes.isNotEmpty()) put("s", strokes.map { stroke(it, index) })
                }
            },
            "c" to 0,
        )
        return Bson.encode(root)
    }

    private fun stroke(s: InkStroke, pageIndex: Int): Map<String, Any?> {
        val highlighter = s.kind == PenKind.HIGHLIGHTER
        val color = if (highlighter && s.color ushr 24 == 0xFF) {
            (s.color and 0x00FFFFFF) or (HIGHLIGHTER_ALPHA shl 24)
        } else {
            s.color
        }
        return linkedMapOf(
            "shape" to null,
            "p" to s.points.filter { it.x.isFinite() && it.y.isFinite() }.map { p ->
                val floats = if (highlighter) floatArrayOf(p.x, p.y) else floatArrayOf(p.x, p.y, p.pressure)
                val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                floats.forEach { buf.putFloat(it) }
                BsonBinary(buf.array())
            },
            "i" to pageIndex,
            "ty" to if (highlighter) "Highlighter" else "fountainPen",
            "pe" to !highlighter,
            // Saber writes ARGB as an unsigned 32-bit value: int32 if it fits, int64 otherwise.
            "c" to (color.toLong() and 0xFFFFFFFFL).let { if (it <= Int.MAX_VALUE) it.toInt() else it },
            "s" to s.width.toDouble(),
            "sp" to false,
        )
    }
}
