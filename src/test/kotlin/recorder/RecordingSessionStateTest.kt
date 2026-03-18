package recorder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingSessionStateTest {
    @Test
    fun `auto start only proceeds when recording should resume and projects exist`() {
        val state = RecordingSessionState()

        assertFalse(
            state.shouldAttemptAutoStart(
                desiredRecordingState = false,
                isRecording = false,
                openProjectCount = 1
            )
        )
        assertFalse(
            state.shouldAttemptAutoStart(
                desiredRecordingState = true,
                isRecording = true,
                openProjectCount = 1
            )
        )
        assertFalse(
            state.shouldAttemptAutoStart(
                desiredRecordingState = true,
                isRecording = false,
                openProjectCount = 0
            )
        )
        assertTrue(
            state.shouldAttemptAutoStart(
                desiredRecordingState = true,
                isRecording = false,
                openProjectCount = 2
            )
        )
    }

    @Test
    fun `snapshot tracking queues initial and changed snapshots but skips unchanged reopen`() {
        val state = RecordingSessionState()
        val documentId = "/tmp/example.py"

        assertTrue(state.shouldQueueSnapshot(documentId, "print('hello')\n"))

        state.rememberDocumentState(documentId, "print('hello')\n")
        assertFalse(state.shouldQueueSnapshot(documentId, "print('hello')\n"))

        assertTrue(state.shouldQueueSnapshot(documentId, "print('goodbye')\n"))
        state.rememberDocumentState(documentId, "print('goodbye')\n")
        assertFalse(state.shouldQueueSnapshot(documentId, "print('goodbye')\n"))
    }

    @Test
    fun `document state presence distinguishes first edit from later edits`() {
        val state = RecordingSessionState()
        val documentId = "/tmp/targets.py"

        assertFalse(state.hasDocumentState(documentId))
        state.rememberDocumentState(documentId, "PLEASE_UPDATE_CLASS_CODE")
        assertTrue(state.hasDocumentState(documentId))
    }
}
