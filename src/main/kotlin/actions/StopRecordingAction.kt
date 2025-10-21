package actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.service
import recorder.EditorRecordingManager

class StopRecordingAction : AnAction() {
    private val logger = Logger.getInstance(StopRecordingAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        logger.info("Stop recording action triggered")
        service<EditorRecordingManager>().stopRecording()
    }
}
