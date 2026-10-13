package com.example.notekonverter.converter

/**
 * Format-neutral handwriting model that both the Samsung (.sdocx) and the
 * Boox (.note) codecs read into and write from.
 *
 * Coordinates are in the source page's own units; [NoteConverter] rescales
 * them when moving between formats with different page widths.
 */
data class InkDocument(
    val title: String,
    val pages: List<InkPage>,
)

data class InkPage(
    val width: Float,
    val height: Float,
    val strokes: List<InkStroke>,
)

enum class PenKind { PEN, HIGHLIGHTER }

data class InkStroke(
    val points: List<InkPoint>,
    /** ARGB colour, e.g. 0xFF000000 for opaque black. */
    val color: Int,
    /** Nominal pen width in page units. */
    val width: Float,
    val kind: PenKind = PenKind.PEN,
    /** Creation time, epoch milliseconds. */
    val createdAt: Long = System.currentTimeMillis(),
)

data class InkPoint(
    val x: Float,
    val y: Float,
    /** Normalised pressure, 0..1. */
    val pressure: Float = 0.5f,
    /** Milliseconds since the first point of the stroke. */
    val time: Int = 0,
)

/** Why a file could not be read; the app shows a localized message per reason. */
enum class NoteError(val message: String) {
    UNKNOWN_FORMAT("Unrecognized file: only Samsung .sdocx, Boox .note and Saber .sba are supported"),
    NOT_SAMSUNG("note.note not found; this is not a Samsung .sdocx file"),
    NOT_BOOX("note/pb/note_info not found; this is not a Boox .note file"),
    NO_PAGES("The note has no pages"),
    SABER_LEGACY("Old Saber .sbn notes are not supported"),
    SABER_NO_NOTE("No .sbn2 note found in the Saber archive"),
    SABER_CORRUPT("The Saber note could not be decoded"),
}

class NoteFormatException(val error: NoteError, detail: String? = null) :
    Exception(if (detail == null) error.message else "${error.message}: $detail")
