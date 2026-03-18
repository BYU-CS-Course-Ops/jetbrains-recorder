package recorder

internal class RecordingSessionState {
    private val recordedDocumentStates: MutableMap<String, String> = mutableMapOf()

    fun clear() {
        recordedDocumentStates.clear()
    }

    fun shouldAttemptAutoStart(
        desiredRecordingState: Boolean,
        isRecording: Boolean,
        openProjectCount: Int
    ): Boolean = desiredRecordingState && !isRecording && openProjectCount > 0

    fun shouldQueueSnapshot(documentId: String, currentText: String): Boolean =
        recordedDocumentStates[documentId] != currentText

    fun hasDocumentState(documentId: String): Boolean = recordedDocumentStates.containsKey(documentId)

    fun rememberDocumentState(documentId: String, currentText: String) {
        recordedDocumentStates[documentId] = currentText
    }
}
