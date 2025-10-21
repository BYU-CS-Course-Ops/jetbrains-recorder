package recorder

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Manages registration of document listeners used to log editor input while a recording session is active.
 */
@Service(Service.Level.APP)
class EditorRecordingManager : Disposable {
    private val logger = Logger.getInstance(EditorRecordingManager::class.java)
    private val eventMulticaster = EditorFactory.getInstance().eventMulticaster
    private var listener: DocumentListener? = null

    fun startRecording() {
        if (listener != null) {
            logger.warn("Recording already active; ignoring start request")
            return
        }

        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.newFragment.isNotEmpty()) {
                    val typedText = event.newFragment.toString().replace("\n", "\\n")
                    logger.info("Recorded input: \"$typedText\" in ${describeDocument(event)}")
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
        logger.info("Editor recording stopped")
    }

    override fun dispose() {
        listener?.let { eventMulticaster.removeDocumentListener(it) }
        listener = null
    }

    private fun describeDocument(event: DocumentEvent): String {
        val file = FileDocumentManager.getInstance().getFile(event.document)
        return file?.let { formatVirtualFile(it) } ?: "unsaved document"
    }

    private fun formatVirtualFile(file: VirtualFile): String = file.presentableUrl
}
