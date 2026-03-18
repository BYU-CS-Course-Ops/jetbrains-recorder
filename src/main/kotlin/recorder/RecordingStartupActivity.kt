package recorder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * Ensures recording starts automatically when projects are opened,
 * handling IDE restarts and plugin updates.
 */
class RecordingStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(RecordingStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        logger.info("Project opened: ${project.name}. Scheduling workspace root refresh.")

        // Schedule with a delay to ensure the project is fully registered in ProjectManager
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                val manager = service<EditorRecordingManager>()

                if (!manager.isRecording() && manager.shouldResumeRecording()) {
                    logger.info("Starting recording after project open: ${project.name}")
                    manager.startRecording()
                } else if (manager.isRecording()) {
                    logger.info("Recording already active. Refreshing workspace roots to include project: ${project.name}")
                    manager.refreshWorkspaceRoots()
                    manager.recordSnapshotsForOpenFiles()
                }
            }
        }, 500, TimeUnit.MILLISECONDS)
    }
}
