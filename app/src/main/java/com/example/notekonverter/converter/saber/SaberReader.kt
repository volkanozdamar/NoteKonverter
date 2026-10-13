package com.example.notekonverter.converter.saber

import com.example.notekonverter.converter.InkDocument
import com.example.notekonverter.converter.InkPage
import com.example.notekonverter.converter.InkPoint
import com.example.notekonverter.converter.InkStroke
import com.example.notekonverter.converter.NoteError
import com.example.notekonverter.converter.NoteFormatException
import com.example.notekonverter.converter.PenKind
import com.example.notekonverter.converter.io.Bson
import com.example.notekonverter.converter.io.BsonBinary
import com.example.notekonverter.converter.io.list
import com.example.notekonverter.converter.io.num
import com.example.notekonverter.converter.io.obj
import com.example.notekonverter.converter.io.readZip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Reads Saber notes: a `.sba` archive (ZIP holding `main.sbn2` plus numbered
 * assets) or a bare `.sbn2` BSON file.
 *
 * Layout of the BSON root (format version `v` 19): `z` is the page list, each
 * page has `w`/`h` and stroke list `s`. A stroke has `p` (points as binary
 * little-endian float32 `x, y[, pressure]`), `c` (ARGB), `s` (pen size), `ty`
 * (tool id) and optional `ox`/`oy` offsets. Typed text, images and PDF
 * backgrounds are not ink and are skipped.
 */
object SaberReader {

    fun read(bytes: ByteArray): InkDocument {
        val main = if (isZip(bytes)) {
            val files = readZip(bytes)
            files.entries.firstOrNull { it.key.lowercase().endsWith(".sbn2") }?.value
                ?: if (files.keys.any { it.lowercase().endsWith(".sbn") }) {
                    throw NoteFormatException(NoteError.SABER_LEGACY)
                } else {
                    throw NoteFormatException(NoteError.SABER_NO_NOTE)
                }
        } else {
            bytes
        }
        val root = runCatching { Bson.decode(main) }
            .getOrElse { throw NoteFormatException(NoteError.SABER_CORRUPT, it.message) }

        val version = root["v"].num()?.toInt() ?: 0
        val pagesJson = root["z"].list().orEmpty().mapNotNull { it.obj() }
        val pages = pagesJson.map { page ->
            val w = page["w"].num()?.toFloat() ?: SaberWriter.PAGE_WIDTH
            val h = page["h"].num()?.toFloat() ?: SaberWriter.PAGE_HEIGHT
            InkPage(w, h, page["s"].list().orEmpty().mapNotNull { parseStroke(it.obj(), version) })
        }.toMutableList()

        // Before format version 8 strokes lived at the root, tagged with their page index.
        val legacy = root["s"].list().orEmpty().mapNotNull { it.obj() }
        if (legacy.isNotEmpty()) {
            val w = root["w"].num()?.toFloat() ?: SaberWriter.PAGE_WIDTH
            val h = root["h"].num()?.toFloat() ?: SaberWriter.PAGE_HEIGHT
            for (json in legacy) {
                val index = json["i"].num()?.toInt() ?: 0
                val stroke = parseStroke(json, version) ?: continue
                while (pages.size <= index) pages += InkPage(w, h, emptyList())
                pages[index] = pages[index].copy(strokes = pages[index].strokes + stroke)
            }
        }
        return InkDocument("", pages)
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun parseStroke(json: Map<String, Any?>?, version: Int): InkStroke? {
        json ?: return null
        val tool = json["ty"] as? String
        if (tool == "Eraser" || tool == "LaserPointer") return null
        val kind = if (tool == "Highlighter") PenKind.HIGHLIGHTER else PenKind.PEN
        var color = json["c"].num()?.toLong()?.toInt() ?: 0xFF000000.toInt()
        // Saber stores highlighters with alpha 100; Samsung and Boox apply their own transparency.
        if (kind == PenKind.HIGHLIGHTER) color = color or 0xFF000000.toInt()
        val width = json["s"].num()?.toFloat() ?: SaberWriter.DEFAULT_SIZE

        val points = when (json["shape"] as? String) {
            "circle" -> circle(json)
            "rect" -> rectangle(json)
            else -> {
                val ox = json["ox"].num()?.toFloat() ?: 0f
                val oy = json["oy"].num()?.toFloat() ?: 0f
                json["p"].list().orEmpty().mapNotNull { point(it, ox, oy, version) }
            }
        }.filter { it.x.isFinite() && it.y.isFinite() }
        if (points.isEmpty()) return null
        return InkStroke(points, color, width, kind, createdAt = 0L)
    }

    private fun point(json: Any?, ox: Float, oy: Float, version: Int): InkPoint? {
        if (json is BsonBinary) {
            val f = ByteBuffer.wrap(json.bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            if (f.remaining() < 2) return null
            val pressure = if (f.remaining() >= 3) f.get(2) else DEFAULT_PRESSURE
            return InkPoint(f.get(0) + ox, f.get(1) + oy, pressure.coerceIn(0f, 1f))
        }
        // Format versions before 13 stored points as {x, y, p} maps.
        val map = json.obj() ?: return null
        if (version >= 13) return null
        val x = map["x"].num()?.toFloat() ?: return null
        val y = map["y"].num()?.toFloat() ?: return null
        return InkPoint(x + ox, y + oy, map["p"].num()?.toFloat()?.coerceIn(0f, 1f) ?: DEFAULT_PRESSURE)
    }

    private fun circle(json: Map<String, Any?>): List<InkPoint> {
        val cx = json["cx"].num()?.toFloat() ?: return emptyList()
        val cy = json["cy"].num()?.toFloat() ?: return emptyList()
        val r = json["r"].num()?.toFloat() ?: return emptyList()
        val n = max(24, (r / 4).toInt()).coerceAtMost(360)
        return (0..n).map { i ->
            val a = 2 * PI * i / n
            InkPoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), DEFAULT_PRESSURE, i)
        }
    }

    private fun rectangle(json: Map<String, Any?>): List<InkPoint> {
        val l = json["rl"].num()?.toFloat() ?: return emptyList()
        val t = json["rt"].num()?.toFloat() ?: return emptyList()
        val r = l + (json["rw"].num()?.toFloat() ?: return emptyList())
        val b = t + (json["rh"].num()?.toFloat() ?: return emptyList())
        return listOf(l to t, r to t, r to b, l to b, l to t).mapIndexed { i, (x, y) -> InkPoint(x, y, DEFAULT_PRESSURE, i) }
    }

    private const val DEFAULT_PRESSURE = 0.5f
}
