package recorder

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Manages registration of document listeners used to log editor input while a recording session is active.
 */
@Service(Service.Level.APP)
class EditorRecordingManager : Disposable {
    private val logger = Logger.getInstance(EditorRecordingManager::class.java)
    private val eventMulticaster = EditorFactory.getInstance().eventMulticaster
    private var listener: DocumentListener? = null
    private var eventQueue: BlockingQueue<QueueItem>? = null
    private var workerThread: Thread? = null
    private var eventWriter: RecordingEventWriter? = null
    private val fileTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm")

    fun startRecording() {
        if (listener != null) {
            logger.warn("Recording already active; ignoring start request")
            return
        }

        val queue = LinkedBlockingQueue<QueueItem>()
        eventQueue = queue
        val sessionTimestamp = fileTimestampFormatter.format(LocalDateTime.now())
        val writer = createWriter(sessionTimestamp)
        eventWriter = writer
        workerThread = startWorker(queue, writer)

        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.newFragment.isNotEmpty() || event.oldFragment.isNotEmpty()) {
                    val queuedEvent = QueuedDocumentChange(
                        timestamp = Instant.now(),
                        document = describeDocument(event),
                        offset = event.offset,
                        oldFragment = event.oldFragment.toString(),
                        newFragment = event.newFragment.toString()
                    )
                    eventQueue?.offer(queuedEvent)
                }
            }
        }

        eventMulticaster.addDocumentListener(documentListener, this)
        listener = documentListener
        logger.info("Editor recording started")
    }

    fun stopRecording() {
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
        } else {
            eventWriter?.close()
        }
        workerThread?.joinSafely()
        workerThread = null
        eventQueue = null
        eventWriter = null
        logger.info("Editor recording stopped")
    }

    override fun dispose() {
        if (listener != null) {
            stopRecording()
        }
    }

    private fun describeDocument(event: DocumentEvent): String {
        val file = FileDocumentManager.getInstance().getFile(event.document)
        return file?.let { formatVirtualFile(it) } ?: "unsaved document"
    }

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
        writer: RecordingEventWriter?
    ): Thread {
        return Thread({
            try {
                while (true) {
                    when (val item = queue.take()) {
                        is QueuedDocumentChange -> {
                            writer?.write(item) ?: logQueuedEvent(item)
                        }

                        StopSignal -> break
                    }
                }
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                writer?.close()
            }
        }, "EditorRecordingManager-Worker").apply {
            isDaemon = true
            start()
        }
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
        }","document":"${change.document.escapeJson()}","offset":${change.offset},"oldFragment":"${change.oldFragment.escapeJson()}","newFragment":"${change.newFragment.escapeJson()}"}"""

    private fun createWriter(timestamp: String): RecordingEventWriter? = try {
        RecordingEventWriter(timestamp).also { writer ->
            writer.outputPath?.let { path ->
                logger.info("Recording events will be written to $path")
            }
        }
    } catch (t: Throwable) {
        logger.warn("Failed to initialize recording writer; events will only be logged", t)
        null
    }

    private sealed interface QueueItem

    private data class QueuedDocumentChange(
        val timestamp: Instant,
        val document: String,
        val offset: Int,
        val oldFragment: String,
        val newFragment: String
    ) : QueueItem

    private data object StopSignal : QueueItem

    private inner class RecordingEventWriter(private val timestamp: String) {
        val outputPath: Path?
        private val writer: BufferedWriter?

        init {
            val projectBasePath = ProjectManager.getInstance().openProjects
                .firstOrNull { it.basePath != null }
                ?.basePath
            if (projectBasePath == null) {
                logger.warn("Unable to locate a project workspace; recording events will only be logged.")
                outputPath = null
                writer = null
            } else {
                val directory = Paths.get(projectBasePath, ".record-editor")
                outputPath = directory.resolve("recording-$timestamp.jsonl")
                writer = try {
                    Files.createDirectories(directory)
                    Files.newBufferedWriter(
                        outputPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    )
                } catch (ioe: IOException) {
                    logger.warn("Failed to open recording file at $outputPath; events will only be logged.", ioe)
                    null
                }
            }
        }

        fun write(change: QueuedDocumentChange) {
            val payload = formatChangeAsJson(change)
            val target = writer
            if (target != null) {
                try {
                    target.write(payload)
                    target.newLine()
                    target.flush()
                } catch (ioe: IOException) {
                    logger.warn("Failed to persist recording event; falling back to IDE log.", ioe)
                    logQueuedEvent(change)
                }
            } else {
                logQueuedEvent(change)
            }
        }

        fun close() {
            try {
                writer?.close()
            } catch (ioe: IOException) {
                logger.warn("Failed to close recording file writer.", ioe)
            }
        }
    }
}
