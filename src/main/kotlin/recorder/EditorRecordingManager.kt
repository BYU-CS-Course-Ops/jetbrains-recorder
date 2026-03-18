package recorder

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@State(name = "EditorRecordingManagerState", storages = [Storage("editorRecorder.xml")])
@Service(Service.Level.APP)
class EditorRecordingManager :
    Disposable,
    PersistentStateComponent<EditorRecordingManager.State> {
    private val logger = Logger.getInstance(EditorRecordingManager::class.java)
    private val eventMulticaster = EditorFactory.getInstance().eventMulticaster
    private var listener: DocumentListener? = null
    private var eventQueue: BlockingQueue<QueueItem>? = null
    private var workerThread: Thread? = null
    private var workspaceRoots: List<Path> = emptyList()
    private val recordedDocumentStates: MutableMap<String, String> = mutableMapOf()
    private var desiredRecordingState: Boolean = false
    private val activeRecordingPaths: MutableSet<Path> = mutableSetOf()


    companion object {
        val RECORDING_STATE_TOPIC: Topic<RecordingStateListener> =
            Topic.create("Record Editor Recording State", RecordingStateListener::class.java)
        val DOCUMENT_RECORDED_TOPIC: Topic<DocumentRecordedListener> =
            Topic.create("Document Change Recorded", DocumentRecordedListener::class.java)
        private const val BATCH_IDLE_MILLIS = 1000L
    }

    data class State(var wasRecording: Boolean = false)

    override fun getState(): State = State(desiredRecordingState)

    override fun loadState(state: State) {
        desiredRecordingState = state.wasRecording
        if (desiredRecordingState) {
            scheduleRecordingResume()
        }
    }

    private fun scheduleRecordingResume() {
        ApplicationManager.getApplication().invokeLater {
            if (!desiredRecordingState || isRecording()) {
                return@invokeLater
            }
            if (ProjectManager.getInstance().openProjects.isEmpty()) {
                logger.info("Deferring auto-start until a project is fully opened")
                return@invokeLater
            }
            if (desiredRecordingState && !isRecording()) {
                logger.info("Attempting to auto-start recording after IDE/plugin initialization")
                startRecording()
            }
        }
    }

    /**
     * Manages registration of document listeners used to log editor input while a recording session is active.
     */
    fun startRecording() {
        if (listener != null) {
            logger.warn("Recording already active; ignoring start request")
            return
        }
        desiredRecordingState = true

        val queue = LinkedBlockingQueue<QueueItem>()
        eventQueue = queue
        workspaceRoots = collectWorkspaceRoots()

        if (workspaceRoots.isEmpty()) {
            logger.warn("No workspace roots found; recording listener will be active but may not record until projects are opened")
        } else {
            logger.info("Recording will monitor ${workspaceRoots.size} workspace root(s)")
        }

        val writer = RecordingEventWriter()
        workerThread = startWorker(queue, writer)
        recordedDocumentStates.clear()
        activeRecordingPaths.clear()

        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                try {
                    if (event.newFragment.isNotEmpty() || event.oldFragment.isNotEmpty()) {
                        val virtualFile = FileDocumentManager.getInstance().getFile(event.document)
                        if (!shouldRecord(virtualFile)) {
                            return
                        }
                        val descriptor = documentDescriptorFor(event.document, virtualFile)
                        recordInitialStateIfNeeded(event, descriptor)
                        val queuedEvent = QueuedDocumentChange(
                            timestamp = Instant.now(),
                            descriptor = descriptor,
                            offset = event.offset,
                            oldFragment = event.oldFragment.toString(),
                            newFragment = event.newFragment.toString()
                        )
                        eventQueue?.offer(queuedEvent)
                        updateRecordedDocumentState(descriptor, event.document.charsSequence.toString())
                        notifyDocumentRecorded()
                    }
                } catch (t: Throwable) {
                    logger.error("Failed to record document change event", t)
                }
            }
        }

        eventMulticaster.addDocumentListener(documentListener, this)
        listener = documentListener
        recordSnapshotsForOpenFiles()
        notifyRecordingStateChanged(true)
        logger.info("Editor recording started")
    }

    fun stopRecording(updatePreference: Boolean = true) {
        if (updatePreference) {
            desiredRecordingState = false
        }
        val toRemove = listener
        if (toRemove == null) {
            logger.warn("Recording not active; ignoring stop request")
            return
        }

        eventMulticaster.removeDocumentListener(toRemove)
        listener = null
        val queue = eventQueue
        queue?.offer(StopSignal)
        workerThread?.joinSafely()
        workerThread = null
        eventQueue = null
        workspaceRoots = emptyList()
        recordedDocumentStates.clear()
        activeRecordingPaths.clear()
        notifyRecordingStateChanged(false)
        RecordingFileDetector.onRecordingStopped()
        logger.info("Editor recording stopped")
    }

    override fun dispose() {
        if (listener != null) {
            stopRecording(updatePreference = false)
        }
    }

    private fun describeDocument(file: VirtualFile?): String =
        file?.let { formatVirtualFile(it) } ?: "unsaved document"

    private fun formatVirtualFile(file: VirtualFile): String = file.presentableUrl

    private fun String.escapeJson(): String = buildString(length) {
        for (ch in this@escapeJson) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> if (ch < ' ') {
                    append(String.format("\\u%04x", ch.code))
                } else {
                    append(ch)
                }
            }
        }
    }

    /**
     * Formats a value as a JSON field value (with proper escaping and quoting).
     */
    private fun formatJsonValue(value: Any): String = when (value) {
        is String -> "\"${value.escapeJson()}\""
        is Boolean, is Number -> value.toString()
        else -> "\"${value.toString().escapeJson()}\""
    }

    /**
     * Builds a JSON object string from a map of fields.
     */
    private fun buildJsonObject(fields: Map<String, Any>): String =
        fields.entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":${formatJsonValue(value)}"
        }

    /**
     * Safely parses a path string, returning null if invalid.
     */
    private fun parsePathSafely(pathString: String, context: String? = null): Path? =
        try {
            Paths.get(pathString).normalize()
        } catch (ipe: InvalidPathException) {
            if (context != null) {
                logger.warn("$context: $pathString", ipe)
            }
            null
        }

    /**
     * Writes JSON lines to a GZIP file, handling the common output stream setup.
     */
    private fun writeToGzipFile(path: Path, lines: List<String>): Boolean =
        try {
            GZIPOutputStream(
                Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
                )
            ).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                lines.forEach { line ->
                    writer.write(line)
                    writer.newLine()
                }
                writer.flush()
            }
            true
        } catch (ioe: IOException) {
            logger.warn("Failed to write to $path", ioe)
            false
        }

    private fun startWorker(
        queue: BlockingQueue<QueueItem>,
        writer: RecordingEventWriter
    ): Thread {
        logger.info("Starting recording worker thread")
        return Thread({
            val batch = mutableListOf<QueuedDocumentChange>()
            try {
                while (true) {
                    when (val item = queue.poll(BATCH_IDLE_MILLIS, TimeUnit.MILLISECONDS)) {
                        is QueuedDocumentChange -> {
                            if (batch.isNotEmpty() && batch.first().descriptor.id != item.descriptor.id) {
                                flushBatch(batch, writer)
                            }
                            batch.add(item)
                            // Track this path for status event broadcasting
                            item.descriptor.outputPath?.let { activeRecordingPaths.add(it) }
                        }

                        is QueuedStatusEvent -> {
                            // Flush any pending batch first
                            flushBatch(batch, writer)
                            // Write status event to all active recording files
                            writer.writeStatusEvent(item, activeRecordingPaths)
                        }

                        StopSignal -> {
                            flushBatch(batch, writer)
                            break
                        }

                        null -> {
                            flushBatch(batch, writer)
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                logger.error(
                    "Recording worker encountered an unexpected error; attempting to flush remaining events.",
                    t
                )
            } finally {
                flushBatch(batch, writer)
                writer.close()
            }
        }, "EditorRecordingManager-Worker").apply {
            isDaemon = true
            start()
        }
    }

    private fun flushBatch(
        batch: MutableList<QueuedDocumentChange>,
        writer: RecordingEventWriter
    ) {
        if (batch.isEmpty()) {
            return
        }
        val descriptor = batch.first().descriptor
        writer.writeBatch(descriptor, batch)
        batch.clear()
    }

    private fun Thread.joinSafely() {
        try {
            join()
        } catch (_: InterruptedException) {
            interrupt()
            Thread.currentThread().interrupt()
        }
    }

    private fun logQueuedEvent(change: QueuedDocumentChange) {
        logger.info(formatChangeAsJson(change))
    }

    private fun formatChangeAsJson(change: QueuedDocumentChange): String = buildJsonObject(
        mapOf(
            "type" to "edit",
            "editor" to "jetbrains",
            "timestamp" to change.timestamp.toString(),
            "document" to change.descriptor.displayName,
            "offset" to change.offset,
            "oldFragment" to change.oldFragment,
            "newFragment" to change.newFragment
        )
    )

    /**
     * Formats a status event as JSON. Reusable for different status types.
     * @param timestamp The timestamp for the event
     * @param statusType The type of status event (e.g., "focusStatus", "activeEdits", "cursorPosition")
     * @param fields Key-value pairs to include in the JSON object
     */
    private fun formatStatusEventAsJson(timestamp: Instant, statusType: String, fields: Map<String, Any>): String =
        buildJsonObject(
            mapOf(
                "type" to statusType,
                "editor" to "jetbrains",
                "timestamp" to timestamp.toString()
            ) + fields
        )

    private fun collectWorkspaceRoots(): List<Path> {
        val openProjects = ProjectManager.getInstance().openProjects
        if (openProjects.isEmpty()) {
            return emptyList()
        }
        return openProjects.mapNotNull { project ->
            project.basePath?.let { parsePathSafely(it, "Skipping project with invalid base path") }
        }
    }

    private fun shouldRecord(virtualFile: VirtualFile?): Boolean {
        if (virtualFile == null || !virtualFile.isInLocalFileSystem) {
            return false
        }
        // Exclude recording files themselves to prevent recursive recording
        if (virtualFile.name.contains(".recording.jsonl.gz")) {
            return false
        }
        // Don't record if we don't have workspace roots yet
        if (workspaceRoots.isEmpty()) {
            return false
        }
        val filePath = parsePathSafely(virtualFile.path) ?: return false
        return workspaceRoots.any { filePath.startsWith(it) }
    }

    fun isRecording(): Boolean = listener != null

    /**
     * Queues a status event to be written to all active recording files.
     * Used for events like focus changes that apply globally.
     */
    fun queueStatusEvent(statusType: String, vararg fields: Pair<String, Any>) {
        val queue = eventQueue ?: return
        queue.offer(
            QueuedStatusEvent(
                timestamp = Instant.now(),
                statusType = statusType,
                fields = fields.toMap()
            )
        )
    }

    /**
     * Refreshes the workspace roots if recording is active.
     * Useful when projects are opened after recording has already started.
     */
    fun refreshWorkspaceRoots() {
        if (!isRecording()) {
            return
        }
        val oldSize = workspaceRoots.size
        val newRoots = collectWorkspaceRoots()
        workspaceRoots = newRoots
        logger.info("Refreshed workspace roots: $oldSize -> ${newRoots.size}")
    }

    fun shouldResumeRecording(): Boolean = desiredRecordingState

    fun recordSnapshotsForOpenFiles() {
        if (!isRecording()) {
            return
        }
        for (project in ProjectManager.getInstance().openProjects) {
            val fileEditorManager = FileEditorManager.getInstance(project)
            for (file in fileEditorManager.openFiles) {
                recordSnapshotIfFileChanged(file)
            }
        }
    }

    fun recordSnapshotIfFileChanged(file: VirtualFile) {
        val queue = eventQueue ?: return
        if (!shouldRecord(file)) {
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val descriptor = documentDescriptorFor(document, file)
        val currentText = document.charsSequence.toString()
        if (recordedDocumentStates[descriptor.id] == currentText) {
            return
        }
        queueSnapshot(queue, descriptor, currentText)
        recordedDocumentStates[descriptor.id] = currentText
    }

    private fun recordInitialStateIfNeeded(event: DocumentEvent, descriptor: DocumentDescriptor) {
        val queue = eventQueue ?: return
        if (recordedDocumentStates.containsKey(descriptor.id)) {
            return
        }
        val initialText = computeDocumentTextBeforeChange(event)
        queueSnapshot(queue, descriptor, initialText)
        recordedDocumentStates[descriptor.id] = initialText
    }

    private fun computeDocumentTextBeforeChange(event: DocumentEvent): String {
        val currentText = event.document.charsSequence.toString()
        if (currentText.isEmpty() && event.newFragment.isEmpty() && event.oldFragment.isEmpty()) {
            return ""
        }
        val builder = StringBuilder(currentText)
        val replaceStart = event.offset.coerceIn(0, builder.length)
        val replaceEnd = (replaceStart + event.newFragment.length).coerceAtMost(builder.length)
        builder.replace(replaceStart, replaceEnd, event.oldFragment.toString())
        return builder.toString()
    }

    private fun queueSnapshot(queue: BlockingQueue<QueueItem>, descriptor: DocumentDescriptor, text: String) {
        val snapshotEvent = QueuedDocumentChange(
            timestamp = Instant.now(),
            descriptor = descriptor,
            offset = 0,
            oldFragment = text,
            newFragment = text
        )
        queue.offer(snapshotEvent)
    }

    private fun updateRecordedDocumentState(descriptor: DocumentDescriptor, currentText: String) {
        recordedDocumentStates[descriptor.id] = currentText
    }

    private sealed interface QueueItem

    private data class QueuedDocumentChange(
        val timestamp: Instant,
        val descriptor: DocumentDescriptor,
        val offset: Int,
        val oldFragment: String,
        val newFragment: String
    ) : QueueItem

    private data class QueuedStatusEvent(
        val timestamp: Instant,
        val statusType: String,
        val fields: Map<String, Any>
    ) : QueueItem

    private data object StopSignal : QueueItem

    private data class DocumentDescriptor(
        val id: String,
        val displayName: String,
        val outputPath: Path?
    )

    private inner class RecordingEventWriter {
        fun writeBatch(descriptor: DocumentDescriptor, batch: List<QueuedDocumentChange>) {
            val targetPath = descriptor.outputPath
            if (targetPath == null) {
                batch.forEach { logQueuedEvent(it) }
                return
            }
            val lines = batch.map { formatChangeAsJson(it) }
            if (!writeToGzipFile(targetPath, lines)) {
                logger.warn("Failed to persist recording batch for ${descriptor.displayName}; falling back to IDE log.")
                batch.forEach { logQueuedEvent(it) }
            }
        }

        fun writeStatusEvent(event: QueuedStatusEvent, paths: Set<Path>) {
            val jsonLine = formatStatusEventAsJson(event.timestamp, event.statusType, event.fields)
            if (paths.isEmpty()) {
                logger.info(jsonLine)
                return
            }
            for (path in paths) {
                writeToGzipFile(path, listOf(jsonLine))
            }
        }

        fun close() {
            // No persistent resources remain open between batches.
        }
    }

    private fun documentDescriptorFor(document: Document, virtualFile: VirtualFile?): DocumentDescriptor {
        val identifier = virtualFile?.path ?: "unsaved@${System.identityHashCode(document)}"
        val outputPath = virtualFile?.let { computeRecordingPath(it) }
        return DocumentDescriptor(
            id = identifier,
            displayName = describeDocument(virtualFile),
            outputPath = outputPath
        )
    }

    private fun computeRecordingPath(file: VirtualFile): Path? =
        try {
            val source = Paths.get(file.path)
            val baseName = file.nameWithoutExtension.ifEmpty { file.name }
            source.resolveSibling("$baseName.recording.jsonl.gz")
        } catch (ipe: InvalidPathException) {
            logger.warn("Unable to resolve recording path for ${file.path}; events will only be logged.", ipe)
            null
        }

    private fun notifyRecordingStateChanged(isRecording: Boolean) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(RECORDING_STATE_TOPIC)
            .recordingStateChanged(isRecording)
    }

    private fun notifyDocumentRecorded() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(DOCUMENT_RECORDED_TOPIC)
            .documentChangeRecorded()
    }
}
