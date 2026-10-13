package com.example.notekonverter.converter.samsung

import com.example.notekonverter.converter.InkDocument
import com.example.notekonverter.converter.InkPage
import com.example.notekonverter.converter.InkPoint
import com.example.notekonverter.converter.InkStroke
import com.example.notekonverter.converter.PenKind
import com.example.notekonverter.converter.io.ByteSink
import com.example.notekonverter.converter.io.ZipBuilder
import com.example.notekonverter.converter.io.hex
import com.example.notekonverter.converter.io.sha256
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Writes a Samsung Notes `.sdocx` archive containing one stroke layer per page.
 *
 * Every structure mirrors what Samsung Notes 4.x writes, including the
 * integrity chain (note.note SHA-256 trailer, object/layer/page identity
 * hashes and pageIdInfo.dat). Constant blocks (title/body text frames and
 * document pen settings) are taken from real Samsung Notes output.
 */
object SdocxWriter {

    /** Width Samsung Notes uses for its default portrait page. */
    const val PAGE_WIDTH = 1440f

    private const val FORMAT_VERSION = 4000
    private const val PEN_ID_FOUNTAIN = 0
    private const val PEN_SETTING_FOUNTAIN = 1
    private const val PEN_ID_MARKER = 2
    private const val MAX_POINTS = 65_535
    private const val MAX_STEP = 1000.0 // keeps every Q10.5 coordinate delta in range

    private val PEN_NAMES = linkedMapOf(
        PEN_SETTING_FOUNTAIN to "18;0;100;",
        PEN_ID_FOUNTAIN to "com.samsung.android.sdk.pen.pen.preload.FountainPen",
        PEN_ID_MARKER to "com.samsung.android.sdk.pen.pen.preload.Marker",
    )

    private val clock = MicroClock()

    fun write(doc: InkDocument): ByteArray {
        val created = clock.next()
        val pages = doc.pages.ifEmpty { listOf(InkPage(PAGE_WIDTH, PAGE_WIDTH * 1.4142f, emptyList())) }
        val noteWidth = pages.first().width.roundToInt()
        val pagePadding = pagePadding(noteWidth)
        // Samsung stacks pages vertically: the document height spans every page plus
        // the gaps between them. Pages outside this extent cannot be edited.
        val noteHeight = pages.sumOf { it.height.roundToInt() } + pagePadding * (pages.size - 1)

        val pageEntries = pages.map { page ->
            val id = UUID.randomUUID().toString()
            val (bytes, hash) = buildPage(page, id)
            Triple(id, bytes, hash)
        }

        val modified = clock.next()
        val note = buildNote(doc.title, noteWidth, noteHeight, pagePadding, created, modified)
        val noteHash = note.copyOfRange(note.size - 32, note.size)

        val pageIdInfo = ByteSink().apply {
            bytes(noteHash)
            u16(pageEntries.size)
            pageEntries.forEach { (id, _, hash) -> utf16(id); bytes(hash) }
        }.toByteArray()

        val mediaInfo = ByteSink().u32(5304).u16(0).bytes("EOFX".toByteArray(Charsets.US_ASCII)).toByteArray()
        val endTag = buildEndTag(noteWidth, noteHeight, pages.size > 1, created, modified)

        val zip = ZipBuilder().file("pageIdInfo.dat", pageIdInfo)
        pageEntries.forEach { (id, bytes, _) -> zip.file("$id.page", bytes) }
        zip.file("media/mediaInfo.dat", mediaInfo)
            .file("note.note", note)
            .file("end_tag.bin", endTag)
        // Samsung appends a second copy of the end tag after the ZIP directory.
        return zip.build() + endTag
    }

    /** Gap between stacked pages; Samsung uses 1/45 of the width (24 @ 1080, 32 @ 1440, 41 @ 1848). */
    fun pagePadding(width: Int): Int = (width / 45f).roundToInt()

    // ---------------------------------------------------------------- note.note

    private fun buildNote(title: String, width: Int, height: Int, padding: Int, created: Long, modified: Long): ByteArray {
        val titleObj = baseFrame(UUID.randomUUID().toString(), modified) + TITLE_FRAME6 + titleFrame7(title) + TEXT_FRAME2
        val bodyObj = baseFrame(UUID.randomUUID().toString(), modified) + BODY_FRAMES

        val fixed = ByteSink()
            .u32(FORMAT_VERSION)
            .utf16("") // note id
            .u32(1) // revision
            .i64(created).i64(modified)
            .u32(width).u32(height)
            .u32(0).u32(padding) // horizontal / vertical page padding
            .u32(FORMAT_VERSION)
            .u32(titleObj.size).bytes(titleObj)
            .u32(bodyObj.size).bytes(bodyObj)
            .toByteArray()

        val headerSize = 4 + 1 + 4 + 1 + 4
        val body = ByteSink()
            .u32(headerSize + fixed.size) // flexible data offset
            .u8(4).u32(NOTE_PROPERTY_MASK)
            .u8(4).u32(NOTE_FIELD_MASK)
            .bytes(fixed)
            .bytes(NOTE_FLEX_PREFIX)
            .bytes(stringTable())
            .bytes(NOTE_FLEX_SUFFIX)
            .toByteArray()
        return body + sha256(body)
    }

    private fun stringTable(): ByteArray {
        val entries = ByteSink().u16(PEN_NAMES.size)
        PEN_NAMES.forEach { (id, s) -> entries.u32(id).utf16(s) }
        val bytes = entries.toByteArray()
        return ByteSink().u32(bytes.size).bytes(bytes).toByteArray()
    }

    private fun titleFrame7(title: String): ByteArray {
        val text = title.take(200)
        val textBytes = ByteSink().apply { text.forEach { u16(it.code) } }.toByteArray()
        val frameSize = TITLE_FRAME7_HEAD.size + 8 + textBytes.size + TITLE_FRAME7_TAIL.size
        val head = TITLE_FRAME7_HEAD.copyOf()
        ByteSink().u32(frameSize).toByteArray().copyInto(head)
        return ByteSink()
            .bytes(head)
            .u32(61 + textBytes.size) // text-common payload size
            .u32(text.length)
            .bytes(textBytes)
            .bytes(TITLE_FRAME7_TAIL)
            .toByteArray()
    }

    /** Type-0 object base frame without flexible fields, as used by title and body. */
    private fun baseFrame(uuid: String, modified: Long): ByteArray = ByteSink()
        .u32(105).u16(0).u32(0)
        .u8(2).u16(0x01BF)
        .u8(4).u32(0)
        .u32(FORMAT_VERSION)
        .u16(36).bytes(uuid.toByteArray(Charsets.US_ASCII))
        .i64(modified)
        .f64(0.0).f64(0.0).f64(0.0).f64(0.0)
        .u32(0).u8(0)
        .toByteArray()

    // ---------------------------------------------------------------- pages

    private fun buildPage(page: InkPage, pageId: String): Pair<ByteArray, ByteArray> {
        val width = page.width.roundToInt()
        val height = page.height.roundToInt()
        val strokes = page.strokes.flatMap(::prepare)

        val objectHashes = ArrayList<ByteArray>()
        val objects = ByteSink()
        for (stroke in strokes) {
            val id = UUID.randomUUID().toString()
            val mtime = clock.next()
            val payload = strokeObject(stroke, id, mtime, width, height)
            val hash = identity(id, mtime)
            objectHashes += hash
            objects.u8(1).u16(0).u32(payload.size + 32).bytes(payload).bytes(hash)
        }

        val layerId = UUID.randomUUID().toString()
        val layerTime = clock.next()
        val layerHash = sha256(*objectHashes.toTypedArray(), identity(layerId, layerTime))
        val pageTime = clock.next()
        val pageHash = sha256(layerHash, identity(pageId, pageTime))

        val bounds = strokes.map(::bbox).reduceOrNull { a, b ->
            doubleArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
        } ?: DoubleArray(4)

        val headerSize = 128
        val flex = ByteSink()
            .f64(bounds[0]).f64(bounds[1]).f64(bounds[2]).f64(bounds[3]) // bit 0: content rect
            .u32(2) // bit 4: background image mode (2 in paged documents)
            .u32(0xFFFCFCFC.toInt()) // bit 5: background colour
            .u32(width) // bit 6: background width
            .toByteArray()
        val layerOffset = headerSize + flex.size

        val out = ByteSink()
            .u32(layerOffset).u32(headerSize)
            .u8(4).u32(0) // property mask
            .u8(4).u32(PAGE_FIELD_MASK)
            .u32(0).u32(width).u32(height).u32(0).u32(0)
            .utf16(pageId)
            .i64(pageTime)
            .u32(FORMAT_VERSION).u32(FORMAT_VERSION)
        check(out.size == headerSize)
        out.bytes(flex)

        out.u16(1).u16(0) // layer count, current layer index
        val layerStart = out.size
        out.u32(98).u32(layerStart + 16)
            .u8(1).u8(0x02) // property: event-forwardable
            .u8(1).u8(0x18) // fields: uuid, modified time
            .u32(0)
            .utf16(layerId).i64(layerTime)
        check(out.size == layerStart + 98)
        out.u32(strokes.size).bytes(objects.toByteArray()).bytes(layerHash)
        out.bytes(pageHash).bytes("Page for SAMSUNG S-Pen SDK".toByteArray(Charsets.US_ASCII))
        return out.toByteArray() to pageHash
    }

    private fun strokeObject(stroke: InkStroke, id: String, mtime: Long, pageW: Int, pageH: Int): ByteArray {
        val box = bbox(stroke)
        val base = ByteSink()
            .u32(121).u16(0).u32(105)
            .u8(2).u16(0x01BF)
            .u8(4).u32(0x6000)
            .u32(FORMAT_VERSION)
            .u16(36).bytes(id.toByteArray(Charsets.US_ASCII))
            .i64(mtime)
            .f64(box[0]).f64(box[1]).f64(box[2]).f64(box[3])
            .u32(0).u8(0)
            .i64(mtime).u32(pageW).u32(pageH) // flexible: append time, owner size
            .toByteArray()
        return base + strokeFrame(stroke)
    }

    private fun strokeFrame(stroke: InkStroke): ByteArray {
        val pts = stroke.points
        val n = pts.size
        val data = ByteSink().u16(n)
        data.f64(pts[0].x.toDouble()).f64(pts[0].y.toDouble())
        var qx = pts[0].x.toDouble()
        var qy = pts[0].y.toDouble()
        for (i in 1 until n) {
            val dx = quantize(pts[i].x - qx, 32.0)
            val dy = quantize(pts[i].y - qy, 32.0)
            qx += dx / 32.0
            qy += dy / 32.0
            data.u16(signMagnitude(dx)).u16(signMagnitude(dy))
        }
        data.f32(pts[0].pressure)
        var qp = pts[0].pressure.toDouble()
        for (i in 1 until n) {
            val d = quantize(pts[i].pressure - qp, 4096.0)
            qp += d / 4096.0
            data.u16(signMagnitude(d))
        }
        data.u32(0)
        for (i in 1 until n) data.u16((pts[i].time - pts[i - 1].time).coerceIn(0, 0xFFFF))
        // Tilt and orientation: constant, upright pen.
        data.f32(0f); repeat(n - 1) { data.u16(0) }
        data.f32(0f); repeat(n - 1) { data.u16(0) }
        data.u8(2).u8(0) // tool type: stylus

        val marker = stroke.kind == PenKind.HIGHLIGHTER
        val fields = if (marker) 0x258C else 0x258E
        val flex = ByteSink().apply {
            if (!marker) u32(PEN_SETTING_FOUNTAIN)
            u32(stroke.color)
            f32(stroke.width)
            u32(if (marker) PEN_ID_MARKER else PEN_ID_FOUNTAIN)
            f32(stroke.width) // fixed width
            u32(1) // particle density
            f32(2.6f) // initial tolerance
        }.toByteArray()

        val pointBytes = data.toByteArray()
        val headerSize = 18
        val flexOffset = headerSize + pointBytes.size
        return ByteSink()
            .u32(flexOffset + flex.size).u16(1).u32(flexOffset)
            .u8(2).u16(0x25) // compressed points + tilt channels + millisecond timestamps
            .u8(4).u32(fields)
            .bytes(pointBytes)
            .bytes(flex)
            .toByteArray()
    }

    /** Densifies long jumps and splits strokes that exceed the u16 point count. */
    private fun prepare(stroke: InkStroke): List<InkStroke> {
        if (stroke.points.isEmpty()) return emptyList()
        val dense = ArrayList<InkPoint>(stroke.points.size)
        dense += stroke.points[0]
        for (p in stroke.points.drop(1)) {
            val prev = dense.last()
            val dist = maxOf(abs(p.x - prev.x), abs(p.y - prev.y)).toDouble()
            val steps = ceil(dist / MAX_STEP).toInt()
            for (k in 1 until steps) {
                val t = k.toFloat() / steps
                dense += InkPoint(prev.x + (p.x - prev.x) * t, prev.y + (p.y - prev.y) * t, p.pressure, p.time)
            }
            dense += p
        }
        return dense.chunked(MAX_POINTS).map { stroke.copy(points = it) }
    }

    private fun bbox(stroke: InkStroke): DoubleArray {
        val xs = stroke.points.map { it.x.toDouble() }
        val ys = stroke.points.map { it.y.toDouble() }
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun quantize(delta: Double, scale: Double): Int = (delta * scale).roundToLong().coerceIn(-0x7FFF, 0x7FFF).toInt()

    private fun signMagnitude(v: Int): Int = if (v < 0) 0x8000 or -v else v

    private fun identity(id: String, time: Long): ByteArray = sha256((id + time.toString()).toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- end tag

    private fun buildEndTag(width: Int, height: Int, paged: Boolean, created: Long, modified: Long): ByteArray {
        val body = ByteSink()
            .u32(FORMAT_VERSION)
            .utf16("") // note id
            .i64(modified)
            .u32(0) // property flags
            .utf16("") // cover image
            .u32(width).f32(height.toFloat())
            .utf16("") // application name
            .u32(-1).u32(-1).utf16("") // app version
            .u32(FORMAT_VERSION)
            .i64(created)
            .u32(0) // last viewed page
            // Page mode 0 = paged (one .page per sheet), 1 = single scrolling page. In mode 1
            // Samsung Notes only shows the first page of a multi-page note.
            .u16(if (paged) 0 else 1).u16(0) // page mode, document type
            .utf16("") // owner
            .u32(0).u32(0) // reserved, encryption blobs
            .i64(created / 1000).i64(modified / 1000) // display times (ms)
            .i64(0)
            .utf16("") // fixed font
            .u32(2).u32(2) // text direction, background theme
            .i64(-1) // server checkpoint
            .u32(0).u32(0)
            .u32(0) // app custom data (empty utf16_u32)
            .bytes("Document for S-Pen SDK".toByteArray(Charsets.US_ASCII))
            .toByteArray()
        return ByteSink().u16(body.size).bytes(body).toByteArray()
    }

    /** Strictly increasing epoch-microsecond timestamps (Samsung's time unit). */
    private class MicroClock {
        private var last = 0L

        @Synchronized
        fun next(): Long {
            last = maxOf(last + 1, System.currentTimeMillis() * 1000)
            return last
        }
    }

    // ------------------------------------------- constant blocks from Samsung Notes output

    private const val NOTE_PROPERTY_MASK = 0x08
    private const val NOTE_FIELD_MASK = 0x0C8E80 // bits 7, 9, 10, 11, 15, 18, 19
    private const val PAGE_FIELD_MASK = 0x71 // content rect, bg mode, bg colour, bg width

    private val TITLE_FRAME6 = hex(
        "4200000006001b0000000100010c0000000004000000000000000013000000010002000000ff0000" +
            "000000000000000000000c000000000000000000000000000000"
    )
    private val TITLE_FRAME7_HEAD = hex(
        "aa00000007003e000000010404011000000400000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000000000"
    )
    private val TITLE_FRAME7_TAIL = hex(
        "00000000010000001400060000000000000001000000000000000000000000007041000000000000" +
            "704100000000000000000000000000000002"
    )
    private val TEXT_FRAME2 = hex("0f0000000200000000000100020000")
    private val BODY_FRAMES = hex(
        "4200000006001b0000000100010c0000000004000000000000000013000000010002000000ff0000" +
            "000000000000000000000c000000000000000000000000000000bd00000007003e00000001040401" +
            "30000004000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000007900000000000000020000001800030000000000000000000000010000000000" +
            "884100000000180001000000000000000000000001000000252525ff000000000100000014000300" +
            "00000000000001000000000000000000000000008041000020410000804100002041000100ffffff" +
            "ff01000000000000000000000002020f0000000200000000000100020000"
    )
    private val NOTE_FLEX_PREFIX = hex("00000000ffffffff0000000000000000") // bits 7, 9
    private val NOTE_FLEX_SUFFIX = hex(
        "000000009e000000330063006f006d002e00730061006d00730075006e0067002e0061006e006400" +
            "72006f00690064002e00730064006b002e00700065006e002e00700065006e002e00700072006500" +
            "6c006f00610064002e0046006f0075006e007400610069006e00500065006e0033332341ff5f33ff" +
            "010000000000000000001e0000000100000000000000000000000f0f6343cdcc4c3f0000803f0000" +
            "10000200000002000000"
    )
}
