package com.example.notekonverter.converter.boox

import com.example.notekonverter.converter.InkDocument
import com.example.notekonverter.converter.InkPage
import com.example.notekonverter.converter.InkPoint
import com.example.notekonverter.converter.InkStroke
import com.example.notekonverter.converter.NoteError
import com.example.notekonverter.converter.NoteFormatException
import com.example.notekonverter.converter.PenKind
import com.example.notekonverter.converter.io.ByteSource
import com.example.notekonverter.converter.io.Json
import com.example.notekonverter.converter.io.first
import com.example.notekonverter.converter.io.list
import com.example.notekonverter.converter.io.num
import com.example.notekonverter.converter.io.obj
import com.example.notekonverter.converter.io.parseProto
import com.example.notekonverter.converter.io.readZip
import java.nio.ByteOrder

/**
 * Reads a Boox (Onyx) Notes `.note` export: a ZIP with protobuf metadata,
 * per-page `#points` binaries and nested shape ZIPs.
 *
 * Layout follows github.com/nrontsis/boox-note-optimizer and
 * github.com/bomberstudios/boox-notes-sync. Only the first note of a
 * multi-note archive is converted.
 */
object BooxNoteReader {

    /** Shape types that carry freehand ink in the `#points` file. */
    private val PEN_TYPES = setOf(2, 5, 21, 22, 60, 61)
    private const val HIGHLIGHTER = 15

    fun read(bytes: ByteArray): InkDocument {
        val files = readZip(bytes)
        val infoName = files.keys.firstOrNull { it.endsWith("note/pb/note_info") }
            ?: throw NoteFormatException(NoteError.NOT_BOOX)
        val root = infoName.removeSuffix("note/pb/note_info")
        val note = files.keys.filter { it.startsWith(root) && !it.contains("/stash/") }

        val info = parseProto(files.getValue(infoName)).let { top ->
            top.first(1)?.takeIf { it.wireType == 2 }?.let { parseProto(it.bytes) } ?: top
        }
        val title = info.first(6)?.string.orEmpty()
        var defaultW = info.first(22)?.float ?: 1860f
        var defaultH = info.first(23)?.float ?: 2480f
        if (defaultW <= 0f || defaultH <= 0f) { defaultW = 1860f; defaultH = 2480f }
        val pageOrder = info.first(20)?.string
            ?.let { runCatching { Json.parse(it).obj()?.get("pageNameList").list() }.getOrNull() }
            ?.mapNotNull { it as? String }
            .orEmpty()
        val pageInfo = info.first(12)?.string
            ?.let { runCatching { Json.parse(it).obj()?.get("pageInfoMap").obj() }.getOrNull() }
            .orEmpty()

        // pointsDoc file contents per page: shapeUUID -> points
        val points = HashMap<String, MutableMap<String, List<InkPoint>>>()
        for (name in note) {
            if (!name.contains("/point/") || !name.endsWith("#points")) continue
            val pageId = name.substringAfterLast('/').substringBefore('#')
            points.getOrPut(pageId) { HashMap() }.putAll(readPoints(files.getValue(name)))
        }

        val strokes = HashMap<String, MutableList<InkStroke>>()
        for (name in note) {
            if (!name.contains("/shape/") || !name.endsWith(".zip")) continue
            val pageId = name.substringAfterLast('/').substringBefore('#')
            val pagePoints = points[pageId] ?: continue
            for (inner in readZip(files.getValue(name)).values) {
                for (record in parseProto(inner)) {
                    if (record.number != 1 || record.wireType != 2) continue
                    parseShape(record.bytes, pagePoints)?.let { strokes.getOrPut(pageId) { ArrayList() } += it }
                }
            }
        }

        // The virtual page records hold the page rectangle the device actually lays out.
        // pageInfoMap can be stale (e.g. still landscape after the note was turned portrait).
        val virtualSizes = HashMap<String, Pair<Float, Float>>()
        for (name in note) {
            if (!name.contains("/virtual/page/pb/")) continue
            runCatching {
                for (record in parseProto(files.getValue(name))) {
                    if (record.number != 1 || record.wireType != 2) continue
                    val f = parseProto(record.bytes)
                    val id = f.first(1)?.string ?: continue
                    rectSize(f.first(6)?.string)?.let { virtualSizes[id] = it }
                }
            }
        }
        if (virtualSizes.isEmpty()) {
            // Older files: fall back to the document-level content size.
            files.keys.firstOrNull { it.startsWith(root) && it.contains("/virtual/doc/pb/") }?.let { name ->
                runCatching {
                    val content = parseProto(files.getValue(name)).first(9)?.string ?: return@runCatching
                    val rect = Json.parse(content).obj()?.get("contentPageSize").obj() ?: return@runCatching
                    rectSize(rect)?.let { (w, h) -> defaultW = w; defaultH = h }
                }
            }
        }

        val ids = pageOrder.ifEmpty { strokes.keys.sorted() }
        if (ids.isEmpty()) throw NoteFormatException(NoteError.NO_PAGES)
        val pages = ids.map { id ->
            val pageStrokes = strokes[id].orEmpty().sortedBy { it.createdAt }
            val dims = pageInfo[id].obj()
            var (w, h) = virtualSizes[id]
                ?: Pair(
                    dims?.get("width").num()?.toFloat()?.takeIf { it > 0 } ?: defaultW,
                    dims?.get("height").num()?.toFloat()?.takeIf { it > 0 } ?: defaultH,
                )
            // Last resort against stale orientation: if the ink only fits the page turned
            // the other way, the stored orientation is wrong.
            val maxX = pageStrokes.maxOfOrNull { s -> s.points.maxOf { it.x } } ?: 0f
            val maxY = pageStrokes.maxOfOrNull { s -> s.points.maxOf { it.y } } ?: 0f
            if ((maxX > w * 1.02f || maxY > h * 1.02f) && maxX <= h * 1.02f && maxY <= w * 1.02f) {
                w = h.also { h = w }
            }
            InkPage(w, h, pageStrokes)
        }
        return InkDocument(title, pages)
    }

    private fun rectSize(json: String?): Pair<Float, Float>? =
        json?.let { runCatching { rectSize(Json.parse(it).obj()) }.getOrNull() }

    private fun rectSize(rect: Map<String, Any?>?): Pair<Float, Float>? {
        rect ?: return null
        val w = (rect["right"].num() ?: return null) - (rect["left"].num() ?: 0.0)
        val h = (rect["bottom"].num() ?: return null) - (rect["top"].num() ?: 0.0)
        return if (w > 0 && h > 0) w.toFloat() to h.toFloat() else null
    }

    private fun parseShape(data: ByteArray, pagePoints: Map<String, List<InkPoint>>): InkStroke? {
        val f = parseProto(data)
        val type = f.first(12)?.varint?.toInt() ?: return null
        if (type !in PEN_TYPES && type != HIGHLIGHTER) return null
        val id = f.first(1)?.string ?: return null
        var pts = pagePoints[id] ?: return null
        if (pts.isEmpty()) return null

        f.first(8)?.string?.let { matrix ->
            val parsed = runCatching { Json.parse(matrix) }.getOrNull()
            val values = (parsed.obj()?.get("values") ?: parsed).list()?.mapNotNull { it.num() }
            if (values != null && values.size >= 6) {
                val (a, b, tx) = values
                val c = values[3]; val d = values[4]; val ty = values[5]
                pts = pts.map { p ->
                    p.copy(x = (a * p.x + b * p.y + tx).toFloat(), y = (c * p.x + d * p.y + ty).toFloat())
                }
            }
        }

        val color = f.first(4)?.varint?.toInt() ?: 0xFF000000.toInt()
        val width = f.first(5)?.float ?: 3f
        val created = f.first(2)?.varint ?: 0L
        val kind = if (type == HIGHLIGHTER) PenKind.HIGHLIGHTER else PenKind.PEN
        return InkStroke(pts, color, width, kind, created)
    }

    /** Parses a `#points` blob: 76-byte header, per-stroke point runs, then a trailing index. */
    private fun readPoints(blob: ByteArray): Map<String, List<InkPoint>> {
        if (blob.size < 80) return emptyMap()
        val s = ByteSource(blob, ByteOrder.BIG_ENDIAN)
        val indexStart = s.i32At(blob.size - 4)
        val out = HashMap<String, List<InkPoint>>()
        var p = indexStart
        while (p + 44 <= blob.size - 4) {
            val id = String(blob, p, 36, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            val offset = s.i32At(p + 36)
            val size = s.i32At(p + 40)
            p += 44
            val n = (size - 4) / 16
            if (offset < 0 || n <= 0 || offset + 4 + n * 16 > blob.size) continue
            s.pos = offset + 4
            var t0 = -1L
            out[id] = List(n) {
                val x = s.f32()
                val y = s.f32()
                s.skip(2) // tilt x, y
                val pressure = s.u16()
                val t = s.u32()
                if (t0 < 0) t0 = t
                InkPoint(x, y, (pressure / 4095f).coerceIn(0f, 1f), (t - t0).toInt().coerceAtLeast(0))
            }
        }
        return out
    }
}
