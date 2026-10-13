package com.example.notekonverter.converter

import com.example.notekonverter.converter.boox.BooxNoteReader
import com.example.notekonverter.converter.boox.BooxNoteWriter
import com.example.notekonverter.converter.io.Bson
import com.example.notekonverter.converter.io.BsonBinary
import com.example.notekonverter.converter.io.ByteSource
import com.example.notekonverter.converter.io.first
import com.example.notekonverter.converter.io.parseProto
import com.example.notekonverter.converter.io.readZip
import com.example.notekonverter.converter.io.sha256
import com.example.notekonverter.converter.saber.SaberReader
import com.example.notekonverter.converter.saber.SaberWriter
import com.example.notekonverter.converter.samsung.SdocxReader
import com.example.notekonverter.converter.samsung.SdocxWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * All notes are generated in code, so the repository ships no third-party
 * or personal sample files. Each writer's output is read back by the
 * matching reader and checked against the format's structural rules.
 */
class NoteConverterTest {

    private val black = 0xFF000000.toInt()
    private val blue = 0xFF335FFF.toInt()
    private val yellow = 0xFFFFEB3B.toInt()

    /** A wavy pen stroke, a coloured pen stroke and a highlighter on every page. */
    private fun sampleDoc(pages: Int, width: Float, height: Float, title: String = "Sample"): InkDocument =
        InkDocument(title, List(pages) { page ->
            InkPage(width, height, listOf(
                wave(width * 0.1f, height * (0.1f + page * 0.01f), width * 0.6f, black, width / 300),
                wave(width * 0.2f, height * 0.5f, width * 0.5f, blue, width / 400),
                InkStroke(
                    List(20) { InkPoint(width * 0.1f + it * width * 0.03f, height * 0.8f, 0.5f, it * 8) },
                    yellow, width / 40, PenKind.HIGHLIGHTER, createdAt = 1_700_000_000_000L,
                ),
            ))
        })

    private fun wave(x: Float, y: Float, length: Float, color: Int, width: Float): InkStroke {
        val n = 120
        return InkStroke(
            List(n) { i ->
                InkPoint(x + length * i / n, y + 40 * sin(i / 6f), pressure = 0.2f + 0.6f * i / n, time = i * 8)
            },
            color, width, PenKind.PEN, createdAt = 1_700_000_000_000L,
        )
    }

    private fun samsungSample(pages: Int = 2) = sampleDoc(pages, SdocxWriter.PAGE_WIDTH, 2036f)
    private fun booxSample(pages: Int = 2) = sampleDoc(pages, BooxNoteWriter.PAGE_WIDTH, BooxNoteWriter.PAGE_HEIGHT)
    private fun saberSample(pages: Int = 2) = sampleDoc(pages, SaberWriter.PAGE_WIDTH, SaberWriter.PAGE_HEIGHT)

    private fun fileOf(format: NoteFormat, doc: InkDocument): ByteArray = when (format) {
        NoteFormat.SAMSUNG_SDOCX -> SdocxWriter.write(doc)
        NoteFormat.BOOX_NOTE -> BooxNoteWriter.write(doc)
        NoteFormat.SABER_SBA -> SaberWriter.write(doc)
    }

    private fun sampleOf(format: NoteFormat, pages: Int = 2) = when (format) {
        NoteFormat.SAMSUNG_SDOCX -> samsungSample(pages)
        NoteFormat.BOOX_NOTE -> booxSample(pages)
        NoteFormat.SABER_SBA -> saberSample(pages)
    }

    @Test
    fun samsungWriterRoundTripsAndKeepsIntegrityChain() {
        val original = samsungSample()
        val bytes = SdocxWriter.write(original)
        val files = readZip(bytes)

        val note = files.getValue("note.note")
        val noteHash = note.copyOfRange(note.size - 32, note.size)
        assertArrayEquals(sha256(note.copyOfRange(0, note.size - 32)), noteHash)
        val pageIdInfo = files.getValue("pageIdInfo.dat")
        assertArrayEquals(noteHash, pageIdInfo.copyOfRange(0, 32))
        // pageIdInfo.dat: note hash, page count, then every page's id and its hash in order.
        val info = ByteSource(pageIdInfo).apply { pos = 32 }
        assertEquals(original.pages.size, info.u16())
        repeat(original.pages.size) {
            val page = files.getValue("${info.utf16()}.page")
            assertArrayEquals(page.copyOfRange(page.size - 58, page.size - 26), info.bytes(32))
            assertEquals("Page for SAMSUNG S-Pen SDK", String(page.copyOfRange(page.size - 26, page.size)))
        }
        assertEquals(0, info.remaining)

        // note.note: flexible area starts right after title/body, whose frame chains fill them exactly.
        val s = ByteSource(note)
        val flexOffset = s.i32()
        s.pos = 14 + 4 + 2 + 4 + 8 + 8 + 4 * 5
        repeat(2) {
            val size = s.i32()
            var frame = s.pos
            while (frame < s.pos + size) frame += ByteSource(note).i32At(frame)
            assertEquals(s.pos + size, frame)
            s.pos += size
        }
        assertEquals(s.pos, flexOffset)

        val reread = SdocxReader.read(bytes)
        assertEquals(original.title, reread.title)
        assertStrokesClose(original, reread, tolerance = 0.05f)
        assertEquals(PenKind.HIGHLIGHTER, reread.pages[0].strokes[2].kind)
    }

    @Test
    fun samsungDocumentHeightStacksAllPagesLikeSamsungNotes() {
        // Samsung Notes: note height = sum of page heights + padding between pages, paged mode.
        for (pages in listOf(1, 2, 6)) {
            val bytes = SdocxWriter.write(samsungSample(pages))
            val (w, h, padding) = noteGeometry(bytes)
            assertEquals(1440, w)
            assertEquals(SdocxWriter.pagePadding(w), padding)
            assertEquals(pages * 2036 + padding * (pages - 1), h)
            // Paged for several pages; Samsung only shows page 1 of a scrolling (mode 1) note.
            assertEquals(if (pages > 1) 0 else 1, endTagPageMode(bytes))
            assertEquals(pages, SdocxReader.read(bytes).pages.size)
        }
    }

    @Test
    fun booxWriterRoundTrips() {
        val original = booxSample()
        val reread = BooxNoteReader.read(BooxNoteWriter.write(original))
        assertEquals(original.title, reread.title)
        assertStrokesClose(original, reread, tolerance = 0.001f)
    }

    /** Boox Notes crashes on import (`ExtraInfoBean.getDocumentId()` on null) without `extra/pb/extra`. */
    @Test
    fun booxWriterAddsExtraInfoLikeTheDevice() {
        val files = readZip(BooxNoteWriter.write(booxSample()))
        val noteId = files.keys.first().substringBefore('/')
        val extra = parseProto(files.getValue("$noteId/extra/pb/extra"))
        assertEquals(1L, extra.first(1)!!.varint)
        assertEquals(noteId, extra.first(4)!!.string)
        val info = parseProto(parseProto(files.getValue("$noteId/note/pb/note_info")).first(1)!!.bytes)
        assertEquals(noteId, info.first(1)!!.string)
        assertEquals(info.first(39)!!.string, extra.first(3)!!.string)
        assertTrue(extra.first(3)!!.string.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun saberWriterRoundTripsAndMatchesSaberLayout() {
        val original = saberSample()
        val bytes = SaberWriter.write(original)
        val files = readZip(bytes)
        assertEquals(setOf("main.sbn2"), files.keys)

        val root = Bson.decode(files.getValue("main.sbn2"))
        assertEquals(19, root["v"])
        val pages = (root["z"] as List<*>).map { it as Map<*, *> }
        assertEquals(1000.0, pages[0]["w"])
        assertEquals(1400.0, pages[0]["h"])
        val strokes = (pages[1]["s"] as List<*>).map { it as Map<*, *> }
        assertTrue(strokes.all { it["i"] == 1 })
        val pen = strokes.first { it["ty"] == "fountainPen" }
        assertEquals(0xFF000000L, pen["c"]) // unsigned ARGB as int64, like Saber
        assertEquals(true, pen["pe"])
        assertEquals(12, (pen["p"] as List<*>).filterIsInstance<BsonBinary>().first().bytes.size)
        val highlighter = strokes.first { it["ty"] == "Highlighter" }
        assertEquals(0x64FFEB3B, highlighter["c"]) // Saber's highlighter alpha 100, fits int32
        assertEquals(8, (highlighter["p"] as List<*>).filterIsInstance<BsonBinary>().first().bytes.size)

        val reread = SaberReader.read(bytes)
        assertStrokesClose(original, reread, tolerance = 0f)
        assertEquals(yellow, reread.pages[0].strokes[2].color) // opaque again for Samsung/Boox
    }

    @Test
    fun readsBareSbn2AndLegacyPointMaps() {
        val sbn2 = SaberWriter.sbn2(saberSample(1))
        assertEquals(3, SaberReader.read(sbn2).pages.single().strokes.size)

        // Format version 12: strokes at the root, points as {x, y, p} maps.
        val legacy = Bson.encode(mapOf(
            "v" to 12, "w" to 1000.0, "h" to 1400.0,
            "s" to listOf(mapOf("i" to 1, "ty" to "Pen", "c" to 0xFF000000L, "s" to 3.0,
                "p" to listOf(mapOf("x" to 10.0, "y" to 20.0, "p" to 0.4), mapOf("x" to 30.0, "y" to 40.0, "p" to 0.6)))),
        ))
        val doc = SaberReader.read(legacy)
        assertEquals(2, doc.pages.size)
        assertEquals(listOf(10f, 30f), doc.pages[1].strokes.single().points.map { it.x })
    }

    @Test
    fun convertsBetweenAllFormats() {
        val dir = File("build/converter-output").apply { mkdirs() }
        for (from in NoteFormat.entries) {
            val source = fileOf(from, sampleOf(from))
            val name = "sample.${from.extension}"
            assertEquals(from, NoteConverter.detect(source, name))
            assertEquals(from, NoteConverter.detect(source)) // by content alone
            for (to in from.targets) {
                val result = NoteConverter.convert(source, name, to)
                assertEquals(from, result.from)
                assertEquals(to, result.to)
                assertEquals("sample.${to.extension}", result.fileName)
                assertEquals(to, NoteConverter.detect(result.bytes))
                assertEquals(2, result.pageCount)
                assertEquals(6, result.strokeCount)
                val back = NoteConverter.read(result.bytes, result.fileName)
                assertEquals(6, back.strokeCount)
                back.document.pages.forEach { page ->
                    assertTrue(page.strokes.all { s -> s.points.all { it.x in 0f..page.width && it.y in 0f..page.height } })
                }
                // Leave outputs behind for inspection with external tools or on a device.
                File(dir, "from_${from.extension}.${to.extension}").writeBytes(result.bytes)
            }
        }
    }

    @Test
    fun longScrollingPageIsCutIntoBooxSheets() {
        val page = InkPage(1440f, 6000f, listOf(100f, 3000f, 5500f).map { y ->
            InkStroke(listOf(InkPoint(100f, y), InkPoint(600f, y + 50)), black, 4f)
        })
        val result = NoteConverter.convert(SdocxWriter.write(InkDocument("Long", listOf(page))), "long.sdocx", NoteFormat.BOOX_NOTE)
        val boox = BooxNoteReader.read(result.bytes)
        assertEquals(3, boox.pages.size)
        assertEquals(listOf(1, 1, 1), boox.pages.map { it.strokes.size })
    }

    @Test
    fun convertsMultiPageBooxNoteToSamsung() {
        val boox = booxSample(pages = 16)
        val result = NoteConverter.convert(BooxNoteWriter.write(boox), "Many pages.note", NoteFormat.SAMSUNG_SDOCX)
        val samsung = SdocxReader.read(result.bytes)
        assertEquals(16, samsung.pages.size)
        val (w, h, padding) = noteGeometry(result.bytes)
        assertEquals(1440, w)
        assertEquals(32, padding)
        assertEquals(16 * 1920 + 15 * 32, h)
        assertEquals(0, endTagPageMode(result.bytes)) // paged, otherwise only page 1 is shown
        samsung.pages.zip(boox.pages).forEach { (sp, bp) ->
            assertEquals(1440f, sp.width)
            assertEquals(1920f, sp.height)
            assertEquals(bp.strokes.map { it.color }, sp.strokes.map { it.color })
            assertTrue(sp.strokes.all { s -> s.points.all { it.x in 0f..1440f && it.y in 0f..1920f } })
        }
    }

    @Test
    fun offersTheOtherTwoFormatsAsTargets() {
        assertEquals(listOf(NoteFormat.BOOX_NOTE, NoteFormat.SABER_SBA), NoteFormat.SAMSUNG_SDOCX.targets)
        assertEquals(listOf(NoteFormat.SAMSUNG_SDOCX, NoteFormat.SABER_SBA), NoteFormat.BOOX_NOTE.targets)
        assertEquals(listOf(NoteFormat.SAMSUNG_SDOCX, NoteFormat.BOOX_NOTE), NoteFormat.SABER_SBA.targets)
    }

    @Test
    fun rejectsUnknownFiles() {
        assertNull(NoteConverter.detect(byteArrayOf(1, 2, 3), "notes.txt"))
        try {
            NoteConverter.read(byteArrayOf(1, 2, 3), "notes.txt")
            fail()
        } catch (e: NoteFormatException) {
            assertEquals(NoteError.UNKNOWN_FORMAT, e.error)
        }
    }

    @Test
    fun bsonRoundTrips() {
        val doc = linkedMapOf<String, Any?>(
            "i" to 7, "l" to 5_000_000_000L, "d" to 1.5, "s" to "çğü", "n" to null, "b" to true,
            "a" to listOf(1, "x", mapOf("k" to 2.0)), "bin" to BsonBinary(byteArrayOf(1, 2, 3)),
        )
        val back = Bson.decode(Bson.encode(doc))
        assertEquals(doc.filterKeys { it != "bin" }, back.filterKeys { it != "bin" })
        assertArrayEquals(byteArrayOf(1, 2, 3), (back["bin"] as BsonBinary).bytes)
    }

    /** Document width/height and vertical page padding stored in note.note. */
    private fun noteGeometry(sdocx: ByteArray): Triple<Int, Int, Int> {
        val s = ByteSource(readZip(sdocx).getValue("note.note"))
        s.pos = 4
        s.skip(s.u8())
        s.skip(s.u8())
        s.skip(4)
        s.utf16()
        s.skip(4 + 8 + 8)
        val w = s.i32()
        val h = s.i32()
        s.skip(4)
        return Triple(w, h, s.i32())
    }

    /** Page mode in end_tag.bin: 0 = paged, 1 = single scrolling page. */
    private fun endTagPageMode(sdocx: ByteArray): Int {
        val s = ByteSource(readZip(sdocx).getValue("end_tag.bin"))
        s.skip(2 + 4) // length, format version
        s.utf16()
        s.skip(8 + 4)
        s.utf16()
        s.skip(4 + 4)
        s.utf16()
        s.skip(4 + 4)
        s.utf16()
        s.skip(4 + 8 + 4)
        return s.u16()
    }

    private fun assertStrokesClose(a: InkDocument, b: InkDocument, tolerance: Float) {
        assertEquals(a.pages.size, b.pages.size)
        a.pages.zip(b.pages).forEach { (pa, pb) ->
            assertEquals(pa.strokes.size, pb.strokes.size)
            pa.strokes.zip(pb.strokes).forEach { (sa, sb) ->
                assertEquals(sa.points.size, sb.points.size)
                assertEquals(sa.color, sb.color)
                assertEquals(sa.kind, sb.kind)
                sa.points.zip(sb.points).forEach { (p, q) ->
                    assertTrue("x ${p.x} vs ${q.x}", abs(p.x - q.x) <= tolerance)
                    assertTrue("y ${p.y} vs ${q.y}", abs(p.y - q.y) <= tolerance)
                    assertTrue("p ${p.pressure} vs ${q.pressure}", abs(p.pressure - q.pressure) <= 0.01f)
                }
            }
        }
    }
}
