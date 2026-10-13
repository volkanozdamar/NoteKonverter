package com.example.notekonverter.converter.boox

import com.example.notekonverter.converter.InkDocument
import com.example.notekonverter.converter.InkPage
import com.example.notekonverter.converter.InkStroke
import com.example.notekonverter.converter.PenKind
import com.example.notekonverter.converter.io.ByteSink
import com.example.notekonverter.converter.io.Json
import com.example.notekonverter.converter.io.ProtoWriter
import com.example.notekonverter.converter.io.ZipBuilder
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Writes a single-note Boox `.note` archive in the layout produced by current
 * Boox Notes firmware (as observed on a Note Air 5C export). Metadata JSON
 * mirrors the device's own output; strokes become fountain-pen (type 5) or
 * highlighter (type 15) shapes.
 */
object BooxNoteWriter {

    const val PAGE_WIDTH = 1860f
    const val PAGE_HEIGHT = 2480f

    private const val PEN_FOUNTAIN = 5
    private const val PEN_HIGHLIGHTER = 15

    fun write(doc: InkDocument): ByteArray {
        val now = System.currentTimeMillis()
        val noteId = compactId()
        val pages = doc.pages.ifEmpty { listOf(InkPage(PAGE_WIDTH, PAGE_HEIGHT, emptyList())) }
        val pageIds = pages.map { compactId() }
        val width = pages.first().width
        val height = pages.first().height
        val title = doc.title.ifBlank { "Converted note" }
        val syncId = objectId(now)

        val zip = ZipBuilder()
        val root = "$noteId/"
        listOf(
            "", "note/", "note/pb/", "template/", "template/json/", "virtual/", "virtual/doc/", "virtual/doc/pb/",
            "virtual/page/", "virtual/page/pb/", "document/", "document/$noteId/", "document/$noteId/template/",
            "document/$noteId/template/json/", "pageModel/", "pageModel/pb/", "resource/", "resource/pb/",
            "point/", "shape/", "extra/", "extra/pb/",
        ).forEach { zip.dir(root + it) }

        zip.file("${root}note/pb/note_info", noteInfo(noteId, title, pageIds, pages, now, syncId))
        zip.file("${root}extra/pb/extra", extraInfo(noteId, syncId))
        zip.file("${root}resource/pb/${UUID.randomUUID()}#$now", ByteArray(0))
        zip.file("${root}virtual/doc/pb/$noteId", virtualDoc(pageIds.first(), width, height, now))
        zip.file("${root}virtual/page/pb/${UUID.randomUUID()}", virtualPages(noteId, pageIds, pages, now))
        zip.file("${root}pageModel/pb/${UUID.randomUUID()}", pageModels(noteId, pageIds, pages, now))

        pages.forEachIndexed { i, page ->
            val pageId = pageIds[i]
            val pointsDocId = UUID.randomUUID().toString()
            val shapeDocId = UUID.randomUUID().toString()
            val strokeIds = page.strokes.map { UUID.randomUUID().toString() }

            zip.dir("${root}point/$pageId/")
            zip.file("${root}point/$pageId/$pageId#$pointsDocId#points", pointsFile(pageId, pointsDocId, page.strokes, strokeIds))

            val shapes = ProtoWriter()
            page.strokes.forEachIndexed { k, stroke ->
                shapes.message(1) { shape(stroke, strokeIds[k], pointsDocId, shapeDocId, now) }
            }
            val inner = "$pageId#$shapeDocId#$now"
            zip.file("${root}shape/$inner.zip", ZipBuilder().file(inner, shapes.toByteArray()).build())
        }
        return zip.build()
    }

    private fun ProtoWriter.shape(s: InkStroke, id: String, pointsDocId: String, shapeDocId: String, now: Long) {
        val xs = s.points.map { it.x }
        val ys = s.points.map { it.y }
        val pad = s.width / 2
        string(1, id)
        varint(2, s.createdAt)
        varint(3, now)
        varint(4, s.color.toLong()) // ARGB, sign-extended like the device writes it
        float(5, s.width)
        string(7, rect(xs.min() - pad, ys.min() - pad, xs.max() + pad, ys.max() + pad))
        string(11, PEN_CONFIG)
        varint(12, if (s.kind == PenKind.HIGHLIGHTER) PEN_HIGHLIGHTER.toLong() else PEN_FOUNTAIN.toLong())
        string(16, pointsDocId)
        string(17, """{"lineStyle":{"phase":0.0,"type":0}}""")
        string(18, shapeDocId)
        string(21, "[]")
        string(26, """{"repo":{}}""")
    }

    /** 76-byte header, `[u32 0][16-byte points...]` per stroke, index, index offset. Big-endian. */
    private fun pointsFile(pageId: String, pointsDocId: String, strokes: List<InkStroke>, ids: List<String>): ByteArray {
        val out = ByteSink(ByteOrder.BIG_ENDIAN)
            .u32(1)
            .bytes(pageId.padEnd(36).toByteArray(Charsets.US_ASCII))
            .bytes(pointsDocId.toByteArray(Charsets.US_ASCII))
        val index = ArrayList<Triple<String, Int, Int>>()
        strokes.forEachIndexed { k, s ->
            val offset = out.size
            out.u32(0)
            for (p in s.points) {
                out.f32(p.x).f32(p.y)
                out.u8(0).u8(0) // tilt
                out.u16((p.pressure * 4095f).roundToInt().coerceIn(0, 4095))
                out.u32(p.time)
            }
            index += Triple(ids[k], offset, out.size - offset)
        }
        val indexStart = out.size
        for ((id, offset, size) in index) {
            out.bytes(id.padEnd(36, '\u0000').toByteArray(Charsets.US_ASCII)).u32(offset).u32(size)
        }
        return out.u32(indexStart).toByteArray()
    }

    /**
     * `extra/pb/extra`: Boox Notes reads the note's document id from here on
     * import and crashes (`ExtraInfoBean.getDocumentId()` on null) without it.
     * Field 2 is copied from a current-firmware export; field 3 repeats
     * note_info field 39.
     */
    private fun extraInfo(noteId: String, syncId: String): ByteArray = ProtoWriter()
        .varint(1, 1)
        .varint(2, EXTRA_VERSION)
        .string(3, syncId)
        .string(4, noteId)
        .toByteArray()

    /** 24-hex id in MongoDB ObjectId shape (seconds timestamp + random), as Boox writes it. */
    private fun objectId(now: Long): String =
        "%08x".format(now / 1000) + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun noteInfo(noteId: String, title: String, pageIds: List<String>, pages: List<InkPage>, now: Long, syncId: String): ByteArray {
        val w = pages.first().width
        val h = pages.first().height
        val pageInfoMap = pageIds.indices.joinToString(",") { i ->
            """"${pageIds[i]}":{"currentLayerId":0,"height":${pages[i].height.roundToInt()},"lastModifyTime":0,""" +
                """"layerList":[{"id":0,"lock":false,"show":true}],"width":${pages[i].width.roundToInt()}}"""
        }
        val canvas = """{"canvasExpandType":"DEFAULT","coverPageId":"","defaultPageRect":{"bottom":${h.roundToInt()},""" +
            """"empty":false,"left":0,"right":${w.roundToInt()},"stability":0,"top":0,"valid":true},""" +
            """"pageInfoMap":{$pageInfoMap},"zoomInfo":{"fitToScreen":false,"scaleType":0,"viewPortHeight":2340.0,""" +
            """"viewPortPos":{"isEmpty":false,"pressure":0.0,"size":0.0,"tiltX":0,"tiltY":0,"timestamp":0,"x":0.0,"y":0.0},""" +
            """"viewPortWidth":1755.0,"viewportScale":0.9435484}}"""
        val bgMap = pageIds.indices.joinToString(",") { i ->
            """"${pageIds[i]}":{"cloud":false,"content":"","global":false,"height":${pages[i].height},"resId":"0",""" +
                """"resIndex":0,"title":"Blank","type":0,"value":"0","visible":true,"width":${pages[i].width}}"""
        }
        val background = """{"bkGroundConfig":{"applyAllPage":false,"asDefault":true,"canvasAutoExpand":false,"scaleType":1},""" +
            """"docBKGround":{"cloud":false,"global":false,"height":0.0,"resIndex":0,"type":0,"visible":true,"width":0.0},""" +
            """"pageBKGroundMap":{$bgMap},"useDocBKGround":false}"""
        val pageList = pageIds.joinToString(",", """{"pageNameList":[""", "]}") { Json.quote(it) }

        return ProtoWriter().message(1) {
            string(1, noteId)
            varint(2, now)
            varint(3, now)
            string(6, title)
            varint(8, 1)
            float(9, 59.055118f)
            float(10, 59.055118f)
            string(11, PEN_SETTINGS)
            string(12, canvas)
            string(13, background)
            string(14, """{"deviceName":"NoteAir5C","size":{"height":$h,"width":$w}}""")
            varint(15, -22016)
            varint(16, PEN_FOUNTAIN.toLong())
            string(20, pageList)
            string(21, """{"pageNameList":[]}""")
            float(22, w)
            float(23, h)
            string(24, "Local")
            varint(31, 1)
            varint(37, 1)
            string(39, syncId)
            varint(40, 1)
            string(44, """{"pageNameList":[]}""")
        }.toByteArray()
    }

    private fun virtualDoc(firstPage: String, w: Float, h: Float, now: Long): ByteArray = ProtoWriter()
        .string(1, compactId())
        .varint(2, now)
        .varint(3, now)
        .string(4, firstPage)
        .float(5, 0.9435484f)
        .string(
            9,
            """{"contentId":"0","contentPageId":"","contentPageSize":${rect(0f, 0f, w, h)},""" +
                """"contentRelativePath":"template/json/$firstPage.template_json","contentType":"geo_layout"}""",
        )
        .toByteArray()

    private fun virtualPages(noteId: String, pageIds: List<String>, pages: List<InkPage>, now: Long): ByteArray {
        val out = ProtoWriter()
        pageIds.forEachIndexed { i, id ->
            val r = rect(0f, 0f, pages[i].width, pages[i].height)
            out.message(1) {
                string(1, id)
                varint(2, now)
                varint(3, now)
                float(4, 1f)
                string(6, r)
                string(7, r)
                string(8, r)
                string(9, "geo_layout")
                string(10, "document/$noteId/template/json/$id.template_json")
                string(12, i.toString())
            }
        }
        return out.toByteArray()
    }

    private fun pageModels(noteId: String, pageIds: List<String>, pages: List<InkPage>, now: Long): ByteArray {
        val out = ProtoWriter()
        (pageIds.indices.map { pageIds[it] to pages[it] } + (noteId to pages.first())).forEach { (id, page) ->
            out.message(1) {
                string(1, id)
                string(2, """{"layerList":[{"id":0,"lock":false,"show":true}]}""")
                varint(5, now)
                varint(6, now)
                string(7, rect(0f, 0f, page.width, page.height))
            }
        }
        return out.toByteArray()
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        """{"bottom":$bottom,"empty":false,"left":$left,"right":$right,"stability":0,"top":$top}"""

    private fun compactId() = UUID.randomUUID().toString().replace("-", "")

    private const val EXTRA_VERSION = 45385L

    private const val PEN_CONFIG =
        """{"alphaFactor":1.0,"displayScale":0.9435484,"dpi":320.0,"maxPressure":4095.0,"newBrushRatio":0.0,""" +
            """"pressure":0.0,"pressureSensitivity":0.3,"smoothLevel":0.2,"source":0,"tiltX":0,"tiltY":0}"""

    private const val PEN_SETTINGS =
        """{"eraseWidthMap":{0:5.905512,1:59.05512,2:5.905512},"fillColor":-7128898,"graphicsShapeColor":-16777216,""" +
            """"graphicsShapeType":7,"normalPenShapeType":5,"penLineStyle":{"lineStyle":{"phase":0.0,"type":0}},""" +
            """"penPressureSensitivityMap":{},"penWithMap":{0:5.905512,1:5.905512,2:5.905512,5:8.267716,7:5.905512,""" +
            """8:5.905512,15:59.05512,21:7.086614,22:7.086614,24:5.905512,25:5.905512,26:5.905512,27:5.905512,""" +
            """60:5.905512,28:5.905512,61:12.401575},"quickPenList":{"quickPens":[{"color":-16777216,"id":"1",""" +
            """"type":5,"width":8.267716},{"color":-22016,"id":"2","type":15,"width":59.05512}],"selectedId":"1"},""" +
            """"shapeLineStyle":{"lineStyle":{"phase":0.0,"type":0}}}"""
}
