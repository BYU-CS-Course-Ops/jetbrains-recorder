package recorder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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

    fun testRecordingSeedsSnapshotForAlreadyOpenFile() {
        val file = createProjectFile("targets.py", "PLEASE_UPDATE_CLASS_CODE\n")
        assertTrue(file.isInLocalFileSystem)
        FileEditorManager.getInstance(project).openFile(file, true)

        manager.startRecording()
        manager.stopRecording()

        val lines = readRecordingLines(recordingPathFor(Paths.get(file.path)))
        assertSize(1, lines)
        assertTrue(lines[0].contains("\"oldFragment\":\"PLEASE_UPDATE_CLASS_CODE\\n\""))
        assertTrue(lines[0].contains("\"newFragment\":\"PLEASE_UPDATE_CLASS_CODE\\n\""))
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

        manager.startRecording()
        manager.queueStatusEvent("focusStatus", "focused" to false)
        manager.stopRecording()

        val firstLines = readRecordingLines(recordingPathFor(Paths.get(firstFile.path)))
        val secondLines = readRecordingLines(recordingPathFor(Paths.get(secondFile.path)))

        assertTrue(firstLines.any { it.contains("\"type\":\"focusStatus\"") && it.contains("\"focused\":false") })
        assertTrue(secondLines.any { it.contains("\"type\":\"focusStatus\"") && it.contains("\"focused\":false") })
    }

    fun testRestartingRecordingAppendsAnotherSnapshotForSameFile() {
        val file = createProjectFile("repeat.py", "print('again')\n")
        FileEditorManager.getInstance(project).openFile(file, true)

        manager.startRecording()
        manager.stopRecording()

        manager.startRecording()
        manager.stopRecording()

        val lines = readRecordingLines(recordingPathFor(Paths.get(file.path)))
        assertSize(2, lines)
        assertTrue(lines.all { it.contains("\"oldFragment\":\"print('again')\\n\"") })
        assertTrue(lines.all { it.contains("\"newFragment\":\"print('again')\\n\"") })
    }

    fun testOnlyEditedFileGetsDeltaWhenMultipleFilesAreOpen() {
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
        val secondLines = readRecordingLines(recordingPathFor(Paths.get(secondFile.path)))

        assertSize(2, firstLines)
        assertSize(1, secondLines)
        assertTrue(firstLines[1].contains("\"oldFragment\":\"alpha\""))
        assertTrue(firstLines[1].contains("\"newFragment\":\"ALPHA\""))
        assertTrue(secondLines[0].contains("\"oldFragment\":\"beta\\n\""))
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
}
