package actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.service
import recorder.EditorRecordingManager

class StartRecordingAction : AnAction() {
    private val logger = Logger.getInstance(StartRecordingAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        logger.info("Start recording action triggered")
        service<EditorRecordingManager>().startRecording()
    }
}
