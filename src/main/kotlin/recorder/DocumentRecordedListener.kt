package recorder

/**
 * Notifies UI components whenever a document change is recorded.
 */
fun interface DocumentRecordedListener {
    fun documentChangeRecorded()
}
