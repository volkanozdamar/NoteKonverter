package com.example.notekonverter

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.notekonverter.converter.NoteConverter
import com.example.notekonverter.converter.NoteError
import com.example.notekonverter.converter.NoteFormatException
import com.example.notekonverter.converter.NoteFormat
import com.example.notekonverter.converter.SourceNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A failure shown to the user: a localized message plus optional technical detail. */
data class Problem(@StringRes val message: Int, val detail: String? = null)

private class AppException(@StringRes val messageRes: Int) : Exception()

private fun Throwable.toProblem(): Problem = when (this) {
    is AppException -> Problem(messageRes)
    is NoteFormatException -> Problem(
        when (error) {
            NoteError.UNKNOWN_FORMAT -> R.string.err_unknown_format
            NoteError.NOT_SAMSUNG -> R.string.err_not_samsung
            NoteError.NOT_BOOX -> R.string.err_not_boox
            NoteError.NO_PAGES -> R.string.err_no_pages
            NoteError.SABER_LEGACY -> R.string.err_saber_legacy
            NoteError.SABER_NO_NOTE -> R.string.err_saber_no_note
            NoteError.SABER_CORRUPT -> R.string.err_saber_corrupt
        },
    )
    else -> Problem(R.string.err_unexpected, message ?: javaClass.simpleName)
}

sealed interface SourceStatus {
    data object Reading : SourceStatus
    data class Ready(val format: NoteFormat, val pages: Int, val strokes: Int) : SourceStatus
    data class Failed(val problem: Problem) : SourceStatus
}

sealed interface OutputStatus {
    /** Waiting for the user to pick an output folder. */
    data object Queued : OutputStatus
    data object Running : OutputStatus
    data class Done(val outputName: String, val outputUri: Uri, val pages: Int, val strokes: Int) : OutputStatus
    data class Failed(val problem: Problem) : OutputStatus
}

data class ConversionJob(
    val id: Long,
    val sourceName: String,
    val source: SourceStatus,
    val outputs: Map<NoteFormat, OutputStatus> = emptyMap(),
) {
    val busy: Boolean
        get() = source is SourceStatus.Reading || outputs.values.any { it is OutputStatus.Running || it is OutputStatus.Queued }
}

/**
 * Reads every added file right away to detect its format, then converts it
 * to whichever of the other formats the user picks, writing the result into
 * the user-chosen output folder.
 */
class ConverterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)
    private val resolver get() = getApplication<Application>().contentResolver
    private val notes = ConcurrentHashMap<Long, SourceNote>()
    private val pending = ArrayList<Pair<Long, NoteFormat>>()
    private var nextId = 0L

    val jobs = mutableStateListOf<ConversionJob>()

    var outputFolder by mutableStateOf(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)?.takeIf(::canWrite))
        private set

    /** True while conversions are waiting for the user to pick an output folder. */
    var needsFolder by mutableStateOf(false)
        private set

    /** High-contrast, animation-free UI for e-paper screens. */
    var einkMode by mutableStateOf(prefs.getBoolean(KEY_EINK, looksLikeEink()))
        private set

    fun setEink(enabled: Boolean) {
        einkMode = enabled
        prefs.edit { putBoolean(KEY_EINK, enabled) }
    }

    fun addFiles(uris: List<Uri>) {
        uris.forEach(::load)
    }

    /** Converts one file to [target]; asks for an output folder first if none is set. */
    fun convert(jobId: Long, target: NoteFormat) {
        if (!notes.containsKey(jobId)) return
        val folder = outputFolder
        if (folder == null) {
            pending += jobId to target
            update(jobId) { it.copy(outputs = it.outputs + (target to OutputStatus.Queued)) }
            needsFolder = true
        } else {
            run(jobId, target, folder)
        }
    }

    /** Converts every loaded file that isn't already in [target] and hasn't been converted to it yet. */
    fun convertAll(target: NoteFormat) {
        jobs.filter { job ->
            val source = job.source as? SourceStatus.Ready ?: return@filter false
            source.format != target && job.outputs[target].let { it == null || it is OutputStatus.Failed }
        }.forEach { convert(it.id, target) }
    }

    fun onFolderPicked(uri: Uri?) {
        needsFolder = false
        val queued = pending.toList()
        pending.clear()
        if (uri == null) {
            queued.forEach { (id, target) -> update(id) { it.copy(outputs = it.outputs - target) } }
            return
        }
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit { putString(KEY_FOLDER, uri.toString()) }
        outputFolder = uri
        queued.forEach { (id, target) -> run(id, target, uri) }
    }

    fun clearFinished() {
        val removed = jobs.filter { !it.busy }
        jobs.removeAll(removed)
        removed.forEach { notes.remove(it.id) }
    }

    private fun load(uri: Uri) {
        val name = displayName(uri)
        val id = nextId++
        jobs.add(0, ConversionJob(id, name, SourceStatus.Reading))
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw AppException(R.string.err_read)
                    val note = NoteConverter.read(bytes, name)
                    notes[id] = note
                    SourceStatus.Ready(note.format, note.pageCount, note.strokeCount)
                }.getOrElse { SourceStatus.Failed(it.toProblem()) }
            }
            update(id) { it.copy(source = status) }
        }
    }

    private fun run(jobId: Long, target: NoteFormat, folder: Uri) {
        val note = notes[jobId] ?: return
        update(jobId) { it.copy(outputs = it.outputs + (target to OutputStatus.Running)) }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching {
                    val result = NoteConverter.convert(note, target)
                    val out = createOutput(folder, result.fileName)
                    resolver.openOutputStream(out, "wt")?.use { it.write(result.bytes) }
                        ?: throw AppException(R.string.err_write)
                    OutputStatus.Done(result.fileName, out, result.pageCount, result.strokeCount)
                }.getOrElse { OutputStatus.Failed(it.toProblem()) }
            }
            update(jobId) { it.copy(outputs = it.outputs + (target to status)) }
        }
    }

    private inline fun update(id: Long, change: (ConversionJob) -> ConversionJob) {
        val index = jobs.indexOfFirst { it.id == id }
        if (index >= 0) jobs[index] = change(jobs[index])
    }

    private fun createOutput(tree: Uri, fileName: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, parent, "application/octet-stream", fileName)
            ?: throw AppException(R.string.err_create)
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "note"
    }

    private fun canWrite(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private companion object {
        const val KEY_FOLDER = "output_folder"
        const val KEY_EINK = "eink_mode"

        /** Makers of Android e-paper tablets (matched against manufacturer, brand and model). */
        val EINK_VENDORS = listOf("onyx", "boox", "bigme", "meebook", "pocketbook", "ratta", "supernote", "remarkable", "kobo", "dasung")

        fun looksLikeEink(): Boolean {
            val id = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}".lowercase()
            return EINK_VENDORS.any { it in id }
        }
    }
}
