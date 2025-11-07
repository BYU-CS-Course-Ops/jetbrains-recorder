package recorder

/**
 * Notifies UI components whenever the recording state changes.
 */
fun interface RecordingStateListener {
    fun recordingStateChanged(isRecording: Boolean)
}
