package recorder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

class EditorRecordingManagerPlatformTest : BasePlatformTestCase() {
    private lateinit var manager: EditorRecordingManager

    override fun setUp() {
        super.setUp()
        manager = ApplicationManager.getApplication().getService(EditorRecordingManager::class.java)
        if (manager.isRecording()) {
            manager.stopRecording()
        }
    }

    override fun tearDown() {
        try {
            if (manager.isRecording()) {
                manager.stopRecording()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testRecordingOpenFileWithoutEditsProducesNoRecording() {
        val file = createProjectFile("targets.py", "PLEASE_UPDATE_CLASS_CODE\n")
        assertTrue(file.isInLocalFileSystem)
        FileEditorManager.getInstance(project).openFile(file, true)

        manager.startRecording()
        manager.stopRecording()

        val recordingPath = recordingPathFor(Paths.get(file.path))
        assertFalse(
            "Recording file should not exist for open file with no edits: $recordingPath",
            Files.exists(recordingPath)
        )
    }

    fun testEditingOpenFileRecordsSnapshotThenDelta() {
        val file = createProjectFile("targets.py", "PLEASE_UPDATE_CLASS_CODE\n")
        FileEditorManager.getInstance(project).openFile(file, true)
        val document = FileDocumentManager.getInstance().getDocument(file)
        assertNotNull(document)

        manager.startRecording()

        WriteCommandAction.runWriteCommandAction(project) {
            document!!.replaceString(0, "PLEASE".length, "class ")
        }

        manager.stopRecording()

        val lines = readRecordingLines(recordingPathFor(Paths.get(file.path)))
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.any { it.contains("\"oldFragment\":\"PLEASE_UPDATE_CLASS_CODE\\n\"") && it.contains("\"newFragment\":\"PLEASE_UPDATE_CLASS_CODE\\n\"") })
        assertTrue(lines.any { it.contains("\"oldFragment\":\"PLEASE\"") && it.contains("\"newFragment\":\"class \"") })
        assertTrue(lines.all { it.contains("\"recorderVersion\":\"${RecorderMetadata.VERSION}\"") })
    }

    fun testStatusEventsAreWrittenToAllActiveRecordingFiles() {
        val firstFile = createProjectFile("first.py", "print('one')\n")
        val secondFile = createProjectFile("second.py", "print('two')\n")
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(firstFile, true)
        editorManager.openFile(secondFile, true)
        val firstDocument = FileDocumentManager.getInstance().getDocument(firstFile)
        val secondDocument = FileDocumentManager.getInstance().getDocument(secondFile)
        assertNotNull(firstDocument)
        assertNotNull(secondDocument)

        manager.startRecording()

        WriteCommandAction.runWriteCommandAction(project) {
            firstDocument!!.replaceString(0, 5, "hello")
            secondDocument!!.replaceString(0, 5, "world")
        }

        manager.queueStatusEvent("focusStatus", "focused" to false)
        manager.stopRecording()

        val firstLines = readRecordingLines(recordingPathFor(Paths.get(firstFile.path)))
        val secondLines = readRecordingLines(recordingPathFor(Paths.get(secondFile.path)))

        assertTrue(firstLines.any { it.contains("\"type\":\"focusStatus\"") && it.contains("\"focused\":false") })
        assertTrue(secondLines.any { it.contains("\"type\":\"focusStatus\"") && it.contains("\"focused\":false") })
    }

    fun testRestartingRecordingWithoutEditsProducesNoRecording() {
        val file = createProjectFile("repeat.py", "print('again')\n")
        FileEditorManager.getInstance(project).openFile(file, true)

        manager.startRecording()
        manager.stopRecording()

        manager.startRecording()
        manager.stopRecording()

        val recordingPath = recordingPathFor(Paths.get(file.path))
        assertFalse(
            "Recording file should not exist for file with no edits across sessions: $recordingPath",
            Files.exists(recordingPath)
        )
    }

    fun testOnlyEditedFileGetsRecordingWhenMultipleFilesAreOpen() {
        val firstFile = createProjectFile("alpha.py", "alpha\n")
        val secondFile = createProjectFile("beta.py", "beta\n")
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(firstFile, true)
        editorManager.openFile(secondFile, false)
        val firstDocument = FileDocumentManager.getInstance().getDocument(firstFile)
        assertNotNull(firstDocument)

        manager.startRecording()

        WriteCommandAction.runWriteCommandAction(project) {
            firstDocument!!.replaceString(0, 5, "ALPHA")
        }

        manager.stopRecording()

        val firstLines = readRecordingLines(recordingPathFor(Paths.get(firstFile.path)))
        val secondRecordingPath = recordingPathFor(Paths.get(secondFile.path))

        assertSize(2, firstLines)
        assertTrue(firstLines[0].contains("\"oldFragment\":\"alpha\\n\""))
        assertTrue(firstLines[0].contains("\"newFragment\":\"alpha\\n\""))
        assertTrue(firstLines[1].contains("\"oldFragment\":\"alpha\""))
        assertTrue(firstLines[1].contains("\"newFragment\":\"ALPHA\""))
        assertFalse(
            "Unedited file should not produce a recording: $secondRecordingPath",
            Files.exists(secondRecordingPath)
        )
    }

    fun testRecoverySnapshotAfterRealEditIsRecorded() {
        val file = createProjectFile("recovery.py", "alpha\n")
        FileEditorManager.getInstance(project).openFile(file, true)
        val document = FileDocumentManager.getInstance().getDocument(file)
        assertNotNull(document)

        manager.startRecording()
        rememberDocumentState(file.path, "stale\n")

        WriteCommandAction.runWriteCommandAction(project) {
            document!!.replaceString(0, 5, "ALPHA")
        }

        manager.stopRecording()

        val lines = readRecordingLines(recordingPathFor(Paths.get(file.path)))
        assertTrue(lines.any { it.contains("\"oldFragment\":\"alpha\\n\"") })
        assertTrue(lines.any { it.contains("\"newFragment\":\"ALPHA\\n\"") })
    }

    fun testSettingChangesReachFilesEditedBeforeAndAfterTheChange() {
        val first = createProjectFile("before.py", "one\n")
        val second = createProjectFile("after.py", "two\n")
        val untouched = createProjectFile("untouched.py", "three\n")
        val editors = FileEditorManager.getInstance(project)
        listOf(first, second, untouched).forEach { editors.openFile(it, false) }
        manager.startRecording()
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(first)!!.insertString(0, "# ")
        }
        manager.queueStatusEvent("userSettingChanged", "setting" to "inlineCompletion.enabled", "value" to true)
        manager.queueStatusEvent("recorderSettingChanged", "setting" to "inlineCompletion.enabled", "value" to false)
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(second)!!.insertString(0, "# ")
        }
        manager.stopRecording()

        for (file in listOf(first, second)) {
            val changes = readRecordingLines(recordingPathFor(Paths.get(file.path)))
                .filter { it.contains("\"type\":\"userSettingChanged\"") || it.contains("\"type\":\"recorderSettingChanged\"") }
            assertSize(2, changes)
            assertTrue(changes[0].contains("\"type\":\"userSettingChanged\""))
            assertTrue(changes[1].contains("\"type\":\"recorderSettingChanged\""))
            assertTrue(changes[0].contains("\"setting\":\"inlineCompletion.enabled\""))
            assertTrue(changes[0].contains("\"value\":true"))
            assertTrue(changes[1].contains("\"value\":false"))
        }
        assertFalse(Files.exists(recordingPathFor(Paths.get(untouched.path))))
    }

    fun testOriginalCompletionPreferenceIsIncludedInPersistentState() {
        for (original in listOf(true, false, null)) {
            val serialized = XmlSerializer.serialize(EditorRecordingManager.State(false, original))
            manager.loadState(XmlSerializer.deserialize(serialized, EditorRecordingManager.State::class.java))
            assertEquals(original, manager.state.originalInlineCompletionEnabled)
        }
        manager.loadState(EditorRecordingManager.State())
        assertNull(manager.state.originalInlineCompletionEnabled)
    }

    private fun recordingPathFor(sourcePath: Path): Path {
        val fileName = sourcePath.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        return sourcePath.resolveSibling("$baseName.recording.jsonl.gz")
    }

    private fun createProjectFile(relativePath: String, text: String): VirtualFile {
        val projectBasePath = project.basePath ?: error("Project base path should be available in platform tests")
        val path = Paths.get(projectBasePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: error("Unable to load test file from local file system: $path")
    }

    private fun readRecordingLines(recordingPath: Path): List<String> {
        assertTrue("Expected recording file to exist: $recordingPath", Files.exists(recordingPath))
        GZIPInputStream(Files.newInputStream(recordingPath)).bufferedReader().use { reader ->
            return reader.readLines()
        }
    }

    private fun rememberDocumentState(documentId: String, text: String) {
        val stateField = EditorRecordingManager::class.java.getDeclaredField("recordingSessionState")
        stateField.isAccessible = true
        val state = stateField.get(manager) as RecordingSessionState
        state.rememberDocumentState(documentId, text)
    }
}
