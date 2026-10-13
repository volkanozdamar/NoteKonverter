package com.example.notekonverter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.example.notekonverter.converter.NoteFormat
import com.example.notekonverter.ui.theme.LocalEink
import com.example.notekonverter.ui.theme.NoteKonverterTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ConverterViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            NoteKonverterTheme(eink = viewModel.einkMode) {
                ConverterScreen(
                    viewModel = viewModel,
                    language = AppLanguage.get(this),
                    onLanguageChange = { language ->
                        AppLanguage.set(this, language)
                        recreate()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Files shared to the app or opened with it are added to the list, ready for a target to be picked. */
    private fun handleIncoming(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        viewModel.addFiles(uris)
    }
}

/** Width from which the controls move into a side panel next to the file grid. */
private val ExpandedWidth = 840.dp

/** Cards are at least this wide; large tablets fit two or three side by side. */
private val CardMinWidth = 340.dp

/** Touch target height: e-paper styluses and fingers both benefit from larger targets. */
private val minTouch: Dp @Composable get() = if (LocalEink.current) 56.dp else 48.dp

private val borderWidth: Dp @Composable get() = if (LocalEink.current) 2.dp else 1.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConverterScreen(
    viewModel: ConverterViewModel,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.addFiles(it)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onFolderPicked(it)
    }
    LaunchedEffect(viewModel.needsFolder) {
        if (viewModel.needsFolder) pickFolder.launch(null)
    }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    val controls: @Composable () -> Unit = {
        Controls(
            viewModel = viewModel,
            onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
            onChooseFolder = { pickFolder.launch(viewModel.outputFolder) },
            language = language,
            onLanguageChange = onLanguageChange,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { showAbout = true }, modifier = Modifier.heightIn(min = minTouch)) {
                        Text(stringResource(R.string.about))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= ExpandedWidth) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                    ) { controls() }
                    VerticalDivider(thickness = borderWidth, color = MaterialTheme.colorScheme.outlineVariant)
                    JobGrid(viewModel, Modifier.weight(1f), contentPadding = PaddingValues(24.dp))
                }
            } else {
                JobGrid(viewModel, Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), header = controls)
            }
        }
    }
}

@Composable
private fun Controls(
    viewModel: ConverterViewModel,
    onAddFiles: () -> Unit,
    onChooseFolder: () -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(8.dp))
        Button(onClick = onAddFiles, modifier = Modifier.fillMaxWidth().heightIn(min = minTouch)) {
            Text(stringResource(R.string.add_files))
        }
        OutlinedButton(
            onClick = onChooseFolder,
            modifier = Modifier.fillMaxWidth().heightIn(min = minTouch),
            border = BorderStroke(borderWidth, MaterialTheme.colorScheme.outline),
        ) {
            Text(stringResource(R.string.choose_folder))
        }
        Text(
            text = viewModel.outputFolder?.let { stringResource(R.string.output_folder, folderLabel(it)) }
                ?: stringResource(R.string.no_output_folder),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minTouch)
                .toggleable(value = viewModel.einkMode, role = Role.Switch, onValueChange = viewModel::setEink),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.eink_mode), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.eink_mode_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = viewModel.einkMode, onCheckedChange = null)
        }
        LanguagePicker(language, onLanguageChange)
        BulkConvert(viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AppLanguage.entries.forEachIndexed { index, language ->
            SegmentedButton(
                selected = language == selected,
                onClick = { if (language != selected) onSelect(language) },
                shape = SegmentedButtonDefaults.itemShape(index, AppLanguage.entries.size),
                border = BorderStroke(borderWidth, MaterialTheme.colorScheme.outline),
                icon = {},
                modifier = Modifier.heightIn(min = minTouch),
            ) {
                Text(
                    when (language) {
                        AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                        // Language names are shown in their own language so they can be found from either.
                        AppLanguage.ENGLISH -> "English"
                        AppLanguage.TURKISH -> "Türkçe"
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** With several files loaded, one tap converts all of them to the same target. */
@Composable
private fun BulkConvert(viewModel: ConverterViewModel) {
    val ready = viewModel.jobs.mapNotNull { (it.source as? SourceStatus.Ready)?.format }
    if (ready.size < 2) return
    HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = borderWidth, color = MaterialTheme.colorScheme.outlineVariant)
    Text(stringResource(R.string.convert_all), style = MaterialTheme.typography.titleSmall)
    NoteFormat.entries.filter { target -> ready.any { it != target } }.forEach { target ->
        OutlinedButton(
            onClick = { viewModel.convertAll(target) },
            modifier = Modifier.fillMaxWidth().heightIn(min = minTouch),
            border = BorderStroke(borderWidth, MaterialTheme.colorScheme.outline),
        ) {
            Text(formatLabel(target))
        }
    }
}

@Composable
private fun JobGrid(
    viewModel: ConverterViewModel,
    modifier: Modifier,
    contentPadding: PaddingValues,
    header: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CardMinWidth),
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = minTouch)) {
                Text(
                    stringResource(R.string.files),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (viewModel.jobs.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearFinished, modifier = Modifier.heightIn(min = minTouch)) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }
        }
        if (viewModel.jobs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.no_files), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(viewModel.jobs, key = { it.id }) { job ->
            JobCard(job, onConvert = { viewModel.convert(job.id, it) })
        }
    }
}

@Composable
private fun JobCard(job: ConversionJob, onConvert: (NoteFormat) -> Unit) {
    val failed = job.source is SourceStatus.Failed
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (failed) borderWidth * 2 else borderWidth, MaterialTheme.colorScheme.outline),
        colors = if (failed && !LocalEink.current) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.outlinedCardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.sourceName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (val source = job.source) {
                SourceStatus.Reading -> Busy(stringResource(R.string.reading))
                is SourceStatus.Failed -> Text(problemText(source.problem))
                is SourceStatus.Ready -> {
                    Text(
                        stringResource(R.string.source_summary, source.format.label, pagesText(source.pages), strokesText(source.strokes)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider(thickness = borderWidth, color = MaterialTheme.colorScheme.outlineVariant)
                    Text(stringResource(R.string.convert_to), style = MaterialTheme.typography.labelLarge)
                    source.format.targets.forEach { target ->
                        TargetRow(target, job.outputs[target], onConvert = { onConvert(target) })
                    }
                }
            }
        }
    }
}

/** One of the formats the file can be converted to: a button until converted, then the result. */
@Composable
private fun TargetRow(target: NoteFormat, status: OutputStatus?, onConvert: () -> Unit) {
    val context = LocalContext.current
    when (status) {
        null -> OutlinedButton(
            onClick = onConvert,
            modifier = Modifier.fillMaxWidth().heightIn(min = minTouch),
            border = BorderStroke(borderWidth, MaterialTheme.colorScheme.outline),
        ) {
            Text(formatLabel(target))
        }
        OutputStatus.Queued -> Busy(stringResource(R.string.target_status, target.label, stringResource(R.string.waiting_for_folder)))
        OutputStatus.Running -> Busy(stringResource(R.string.target_status, target.label, stringResource(R.string.converting)))
        is OutputStatus.Done -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = minTouch)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.saved, target.label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.output_summary, status.outputName, pagesText(status.pages), strokesText(status.strokes)),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, status.outputUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, status.outputName))
            }, modifier = Modifier.heightIn(min = minTouch)) { Text(stringResource(R.string.share)) }
        }
        is OutputStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = minTouch)) {
            Text(
                stringResource(R.string.target_status, target.label, problemText(status.problem)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onConvert, modifier = Modifier.heightIn(min = minTouch)) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** Progress line; on e-paper a spinning indicator would keep the panel refreshing, so it is text only. */
@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = minTouch)) {
        if (!LocalEink.current) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/** Version, data-loss warning, trademark disclaimer and licence. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = minTouch)) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.about_description))
                Text(stringResource(R.string.about_data_loss_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_data_loss))
                Text(stringResource(R.string.about_trademarks_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_trademarks))
                Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
            }
        },
        shape = if (LocalEink.current) MaterialTheme.shapes.small else AlertDialogDefaults.shape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = if (LocalEink.current) 0.dp else AlertDialogDefaults.TonalElevation,
    )
}

@Composable
private fun pagesText(n: Int) = pluralStringResource(R.plurals.pages, n, n)

@Composable
private fun strokesText(n: Int) = pluralStringResource(R.plurals.strokes, n, n)

@Composable
private fun problemText(problem: Problem): String {
    val message = stringResource(R.string.failed, stringResource(problem.message))
    return if (problem.detail == null) message else "$message (${problem.detail})"
}

@Composable
private fun formatLabel(format: NoteFormat) = stringResource(R.string.target_label, format.label, format.extension)

private fun folderLabel(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "/" } }
        .getOrDefault(uri.toString())
