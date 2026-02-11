package recorder

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Monitors opened files and detects if there are existing recording files.
 * If recording is stopped and a recording file exists for an opened file,
 * prompts the user to resume recording.
 *
 * Behavior:
 * - If user dismisses the prompt, don't ask again this session
 * - If user accepts (resumes recording), reset state so we can ask again if recording stops
 */
class RecordingFileDetector : FileEditorManagerListener {
    private val logger = Logger.getInstance(RecordingFileDetector::class.java)

    companion object {
        private val promptedFiles: MutableSet<String> = mutableSetOf()
        // Tracks if user has dismissed (not accepted) a prompt this session
        private var userDismissedThisSession: Boolean = false
        // Tracks if user accepted and we should allow prompting again when recording stops
        private var userAcceptedResume: Boolean = false

        /**
         * Called when recording stops. If user previously accepted a resume prompt,
         * reset state to allow prompting again.
         */
        fun onRecordingStopped() {
            if (userAcceptedResume) {
                userAcceptedResume = false
                promptedFiles.clear()
            }
        }

        /**
         * Called when user accepts the resume prompt.
         */
        fun onUserAcceptedResume() {
            userAcceptedResume = true
            userDismissedThisSession = false
        }

        /**
         * Called when user dismisses the prompt.
         */
        fun onUserDismissed() {
            userDismissedThisSession = true
        }
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        checkForExistingRecording(source.project, file)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        event.newFile?.let {
            val project = event.manager.project
            checkForExistingRecording(project, it)
        }
    }

    private fun checkForExistingRecording(project: Project, file: VirtualFile) {
        // Skip if recording is already active
        val manager = service<EditorRecordingManager>()
        if (manager.isRecording()) {
            return
        }

        // Skip non-local files
        if (!file.isInLocalFileSystem) {
            return
        }

        // Skip recording files themselves
        if (file.name.contains(".recording.jsonl.gz")) {
            return
        }

        // Don't prompt again if user dismissed this session
        if (userDismissedThisSession) {
            return
        }

        // Check if we've already prompted for this specific file
        val fileKey = file.path
        if (promptedFiles.contains(fileKey)) {
            return
        }

        // Check if a recording file exists for this file
        val recordingPath = computeRecordingPath(file)
        if (recordingPath != null && Files.exists(recordingPath)) {
            promptedFiles.add(fileKey)
            showResumeRecordingPrompt(project, file)
        }
    }

    private fun computeRecordingPath(file: VirtualFile): java.nio.file.Path? {
        return try {
            val source = Paths.get(file.path)
            val baseName = file.nameWithoutExtension.ifEmpty { file.name }
            source.resolveSibling("$baseName.recording.jsonl.gz")
        } catch (e: Exception) {
            logger.warn("Unable to resolve recording path for ${file.path}", e)
            null
        }
    }

    private fun showResumeRecordingPrompt(project: Project, file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeRecorder.Notifications")
                .createNotification(
                    "Recording File Detected",
                    "A recording file exists for '${file.name}'. Would you like to resume recording?",
                    NotificationType.INFORMATION
                )
                .addAction(ResumeRecordingAction())
                .addAction(DismissNotificationAction())
                .notify(project)
        }
    }

    private class ResumeRecordingAction : NotificationAction("Resume Recording") {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            onUserAcceptedResume()
            service<EditorRecordingManager>().startRecording()
            notification.expire()
        }
    }

    private class DismissNotificationAction : NotificationAction("Dismiss") {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            onUserDismissed()
            notification.expire()
        }
    }
}




