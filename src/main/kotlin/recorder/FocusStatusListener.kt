package recorder

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.IdeFrame

/**
 * Listens for IDE application activation/deactivation events and queues focus status changes.
 * Registered via plugin.xml to ensure it's active from IDE startup.
 */
class FocusStatusListener : ApplicationActivationListener {

    override fun applicationActivated(ideFrame: IdeFrame) {
        service<EditorRecordingManager>().queueStatusEvent("focusStatus", "focused" to true)
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        service<EditorRecordingManager>().queueStatusEvent("focusStatus", "focused" to false)
    }
}
