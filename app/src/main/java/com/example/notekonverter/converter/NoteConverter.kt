package com.example.notekonverter.converter

import com.example.notekonverter.converter.boox.BooxNoteReader
import com.example.notekonverter.converter.boox.BooxNoteWriter
import com.example.notekonverter.converter.saber.SaberReader
import com.example.notekonverter.converter.saber.SaberWriter
import com.example.notekonverter.converter.samsung.SdocxReader
import com.example.notekonverter.converter.samsung.SdocxWriter
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

enum class NoteFormat(val extension: String, val label: String) {
    SAMSUNG_SDOCX("sdocx", "Samsung Notes"),
    BOOX_NOTE("note", "Boox Notes"),
    SABER_SBA("sba", "Saber"),
    ;

    /** Formats a note in this format can be converted to. */
    val targets: List<NoteFormat> get() = entries.filter { it != this }
}

/** A parsed input file, ready to be written in any of [NoteFormat.targets]. */
class SourceNote(val format: NoteFormat, val document: InkDocument, val baseName: String) {
    val pageCount: Int get() = document.pages.size
    val strokeCount: Int get() = document.pages.sumOf { it.strokes.size }
}

class ConversionResult(
    val from: NoteFormat,
    val to: NoteFormat,
    val bytes: ByteArray,
    val fileName: String,
    val pageCount: Int,
    val strokeCount: Int,
)

/** Detects the format of an added file and converts it to a chosen other format. */
object NoteConverter {

    /** Detects by archive content, falling back to the file extension. */
    fun detect(bytes: ByteArray, fileName: String? = null): NoteFormat? {
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val name = zin.nextEntry?.name ?: break
                    if (name == "note.note" || name == "pageIdInfo.dat") return NoteFormat.SAMSUNG_SDOCX
                    if (name.endsWith("note/pb/note_info") || name == "note_tree") return NoteFormat.BOOX_NOTE
                    if (name.lowercase().let { it.endsWith(".sbn2") || it.endsWith(".sbn") }) return NoteFormat.SABER_SBA
                }
            }
        }
        return when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "sdocx" -> NoteFormat.SAMSUNG_SDOCX
            "note" -> NoteFormat.BOOX_NOTE
            "sba", "sbn2" -> NoteFormat.SABER_SBA
            else -> null
        }
    }

    fun read(bytes: ByteArray, fileName: String? = null): SourceNote {
        val format = detect(bytes, fileName)
            ?: throw NoteFormatException(NoteError.UNKNOWN_FORMAT)
        val doc = when (format) {
            NoteFormat.SAMSUNG_SDOCX -> SdocxReader.read(bytes)
            NoteFormat.BOOX_NOTE -> BooxNoteReader.read(bytes)
            NoteFormat.SABER_SBA -> SaberReader.read(bytes)
        }
        val base = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: doc.title.ifBlank { "note" }
        // Saber keeps no title inside the file; the file name is the note's name there.
        val titled = if (doc.title.isBlank()) doc.copy(title = base) else doc
        return SourceNote(format, titled, base)
    }

    fun convert(source: SourceNote, target: NoteFormat): ConversionResult {
        require(target != source.format) { "Source and target format are the same" }
        val doc = layoutFor(target, source.document)
        val out = when (target) {
            NoteFormat.SAMSUNG_SDOCX -> SdocxWriter.write(doc)
            NoteFormat.BOOX_NOTE -> BooxNoteWriter.write(doc)
            NoteFormat.SABER_SBA -> SaberWriter.write(doc)
        }
        return ConversionResult(
            from = source.format,
            to = target,
            bytes = out,
            fileName = "${source.baseName}.${target.extension}",
            pageCount = doc.pages.size,
            strokeCount = doc.pages.sumOf { it.strokes.size },
        )
    }

    fun convert(bytes: ByteArray, fileName: String?, target: NoteFormat): ConversionResult =
        convert(read(bytes, fileName), target)

    private fun layoutFor(target: NoteFormat, doc: InkDocument): InkDocument = when (target) {
        NoteFormat.BOOX_NOTE -> toSheets(doc, BooxNoteWriter.PAGE_WIDTH, BooxNoteWriter.PAGE_HEIGHT)
        NoteFormat.SAMSUNG_SDOCX -> fitToWidth(doc, SdocxWriter.PAGE_WIDTH)
        NoteFormat.SABER_SBA -> fitToWidth(doc, SaberWriter.PAGE_WIDTH)
    }

    /**
     * Boox pages have a fixed size. A page of ordinary proportions is scaled
     * to fit one sheet; a long scrolling page (e.g. Samsung's endless mode) is
     * scaled to the sheet width and cut into sheets.
     */
    fun toSheets(doc: InkDocument, pageW: Float, pageH: Float): InkDocument {
        val out = ArrayList<InkPage>()
        for (page in doc.pages) {
            val srcW = page.width.takeIf { it > 0 } ?: pageW
            val srcH = page.height.takeIf { it > 0 } ?: pageH
            val strokes = page.strokes.filter { it.points.isNotEmpty() }
            val widthScale = pageW / srcW
            if (srcH * widthScale <= pageH * LONG_PAGE_RATIO) {
                val scale = min(widthScale, pageH / srcH)
                val dx = (pageW - srcW * scale) / 2
                out += InkPage(pageW, pageH, strokes.map { it.scaled(scale, dx) })
                continue
            }
            val scaled = strokes.map { it.scaled(widthScale) }
            val bottom = scaled.maxOfOrNull { s -> s.points.maxOf { it.y } } ?: 0f
            val sheets = maxOf(1, ceil(bottom / pageH).toInt())
            val buckets = List(sheets) { ArrayList<InkStroke>() }
            for (s in scaled) {
                val top = s.points.minOf { it.y }
                val sheet = floor(top / pageH).toInt().coerceIn(0, sheets - 1)
                val dy = sheet * pageH
                buckets[sheet] += if (dy == 0f) s else s.copy(points = s.points.map { it.copy(y = it.y - dy) })
            }
            buckets.forEach { out += InkPage(pageW, pageH, it) }
        }
        return InkDocument(doc.title, out)
    }

    /** Pages map one-to-one, scaled to the target width with their aspect ratio kept. */
    fun fitToWidth(doc: InkDocument, width: Float): InkDocument {
        val pages = doc.pages.map { page ->
            val scale = width / (page.width.takeIf { it > 0 } ?: width)
            InkPage(width, page.height * scale, page.strokes.filter { it.points.isNotEmpty() }.map { it.scaled(scale) })
        }
        return InkDocument(doc.title, pages)
    }

    /** Pages up to this much taller than a target sheet are shrunk to fit instead of being split. */
    private const val LONG_PAGE_RATIO = 1.25f

    private fun InkStroke.scaled(k: Float, dx: Float = 0f) = copy(
        points = points.map { it.copy(x = it.x * k + dx, y = it.y * k) },
        width = width * k,
    )
}
