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
    private val recordedInitialStateKeys: MutableSet<String> = mutableSetOf()
    private var desiredRecordingState: Boolean = false

    companion object {
        val RECORDING_STATE_TOPIC: Topic<RecordingStateListener> =
            Topic.create("Record Editor Recording State", RecordingStateListener::class.java)
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
            if (desiredRecordingState && !isRecording()) {
                startRecording()
            }
        }
    }

    /**
     * Manages registration of document listeners used to log editor input while a recording session is active.
     */
    fun startRecording() {
        desiredRecordingState = true
        if (listener != null) {
            logger.warn("Recording already active; ignoring start request")
            return
        }

        val queue = LinkedBlockingQueue<QueueItem>()
        eventQueue = queue
        workspaceRoots = collectWorkspaceRoots()
        val writer = RecordingEventWriter()
        workerThread = startWorker(queue, writer)
        recordedInitialStateKeys.clear()

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
                    }
                } catch (t: Throwable) {
                    logger.error("Failed to record document change event", t)
                }
            }
        }

        eventMulticaster.addDocumentListener(documentListener, this)
        listener = documentListener
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
        if (queue != null) {
            queue.offer(StopSignal)
        }
        workerThread?.joinSafely()
        workerThread = null
        eventQueue = null
        workspaceRoots = emptyList()
        recordedInitialStateKeys.clear()
        notifyRecordingStateChanged(false)
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

    private fun startWorker(
        queue: BlockingQueue<QueueItem>,
        writer: RecordingEventWriter
    ): Thread {
        logger.info("Starting recording worker thread")
        return Thread({
            val batch = mutableListOf<QueuedDocumentChange>()
            try {
                while (true) {
                    val item = queue.poll(BATCH_IDLE_MILLIS, TimeUnit.MILLISECONDS)
                    when (item) {
                        is QueuedDocumentChange -> {
                            if (batch.isNotEmpty() && batch.first().descriptor.id != item.descriptor.id) {
                                flushBatch(batch, writer)
                            }
                            batch.add(item)
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
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                logger.error("Recording worker encountered an unexpected error; attempting to flush remaining events.", t)
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
        } catch (ie: InterruptedException) {
            interrupt()
            Thread.currentThread().interrupt()
        }
    }

    private fun logQueuedEvent(change: QueuedDocumentChange) {
        logger.info(formatChangeAsJson(change))
    }

    private fun formatChangeAsJson(change: QueuedDocumentChange): String =
        """{"timestamp":"${
            change.timestamp.toString().escapeJson()
        }","document":"${change.descriptor.displayName.escapeJson()}","offset":${change.offset},"oldFragment":"${change.oldFragment.escapeJson()}","newFragment":"${change.newFragment.escapeJson()}"}"""

    private fun collectWorkspaceRoots(): List<Path> {
        val openProjects = ProjectManager.getInstance().openProjects
        if (openProjects.isEmpty()) {
            return emptyList()
        }
        val roots = mutableListOf<Path>()
        for (project in openProjects) {
            val basePath = project.basePath ?: continue
            try {
                roots.add(Paths.get(basePath).normalize())
            } catch (ipe: InvalidPathException) {
                logger.warn("Skipping project with invalid base path: $basePath", ipe)
            }
        }
        return roots
    }

    private fun shouldRecord(virtualFile: VirtualFile?): Boolean {
        if (virtualFile == null || !virtualFile.isInLocalFileSystem) {
            return false
        }
        // Exclude recording files themselves to prevent recursive recording
        if (virtualFile.name.contains(".recording.jsonl.gz")) {
            return false
        }
        if (workspaceRoots.isEmpty()) {
            workspaceRoots = collectWorkspaceRoots()
            if (workspaceRoots.isEmpty()) {
                return false
            }
        }
        val filePath = try {
            Paths.get(virtualFile.path).normalize()
        } catch (ipe: InvalidPathException) {
            if (logger.isDebugEnabled) {
                logger.debug("Skipping document with invalid path: ${virtualFile.path}", ipe)
            }
            return false
        }
        if (workspaceRoots.isEmpty()) {
            return false
        }
        return workspaceRoots.any { filePath.startsWith(it) }
    }

    fun isRecording(): Boolean = listener != null

    private fun recordInitialStateIfNeeded(event: DocumentEvent, descriptor: DocumentDescriptor) {
        val queue = eventQueue ?: return
        if (!recordedInitialStateKeys.add(descriptor.id)) {
            return
        }
        val initialText = computeDocumentTextBeforeChange(event)
        val snapshotEvent = QueuedDocumentChange(
            timestamp = Instant.now(),
            descriptor = descriptor,
            offset = 0,
            oldFragment = initialText,
            newFragment = initialText
        )
        queue.offer(snapshotEvent)
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

    private sealed interface QueueItem

    private data class QueuedDocumentChange(
        val timestamp: Instant,
        val descriptor: DocumentDescriptor,
        val offset: Int,
        val oldFragment: String,
        val newFragment: String
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
            try {
                Files.newOutputStream(
                    targetPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
                ).use { fileStream ->
                    GZIPOutputStream(fileStream).bufferedWriter(StandardCharsets.UTF_8).use { gzipWriter ->
                        batch.forEach { change ->
                            gzipWriter.write(formatChangeAsJson(change))
                            gzipWriter.newLine()
                        }
                    }
                }
            } catch (ioe: IOException) {
                logger.warn("Failed to persist recording batch for ${descriptor.displayName}; falling back to IDE log.", ioe)
                batch.forEach { logQueuedEvent(it) }
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
}
