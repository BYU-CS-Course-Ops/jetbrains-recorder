package recorder

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.IdeFrame

/**
 * Listens for IDE application activation/deactivation events and queues focus status changes.
 * Registered via plugin.xml to ensure it's active from IDE startup.
 */
class FocusStatusListener : ApplicationActivationListener {
    private val logger = Logger.getInstance(FocusStatusListener::class.java)

    override fun applicationActivated(ideFrame: IdeFrame) {
        logger.debug("IDE application activated; queueing recorder focus status")
        service<EditorRecordingManager>().queueStatusEvent("focusStatus", "focused" to true)
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        logger.debug("IDE application deactivated; queueing recorder focus status")
        service<EditorRecordingManager>().queueStatusEvent("focusStatus", "focused" to false)
    }
}
