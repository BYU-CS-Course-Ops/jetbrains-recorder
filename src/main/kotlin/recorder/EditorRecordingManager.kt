package recorder

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.time.Instant
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

    fun startRecording() {
        if (listener != null) {
            logger.warn("Recording already active; ignoring start request")
            return
        }

        val queue = LinkedBlockingQueue<QueueItem>()
        eventQueue = queue
        workerThread = startWorker(queue)

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
        eventQueue?.offer(StopSignal)
        workerThread?.joinSafely()
        workerThread = null
        eventQueue = null
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

    private fun startWorker(queue: BlockingQueue<QueueItem>): Thread {
        return Thread({
            while (true) {
                when (val item = queue.take()) {
                    is QueuedDocumentChange -> logQueuedEvent(item)
                    StopSignal -> return@Thread
                }
            }
        }, "EditorRecordingManager-Logger").apply {
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
        val message =
            """{"timestamp":"${change.timestamp.toString().escapeJson()}","document":"${change.document.escapeJson()}","offset":${change.offset},"oldFragment":"${change.oldFragment.escapeJson()}","newFragment":"${change.newFragment.escapeJson()}"}"""
        logger.info(message)
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
}
