package recorder

/** Keeps the original preference separate from the temporary value imposed by recording. */
internal class InlineCompletionPolicy(
    private val read: () -> Boolean,
    private val write: (Boolean) -> Unit,
    private val savedOriginal: () -> Boolean?,
    private val saveOriginal: (Boolean?) -> Unit,
    private val changed: (SettingChange) -> Unit
) {
    private var lastObserved: Boolean? = null
    private var active = false
    private var pendingWrite: Boolean? = null

    fun start() {
        active = true
        check()
    }

    fun check() {
        if (!active) return
        val current = read()
        if (lastObserved == null) {
            if (savedOriginal() == null) saveOriginal(current)
            lastObserved = current
        } else {
            observeCurrent(current)
        }
        if (lastObserved == true) {
            pendingWrite = false
            write(false)
            val actual = read()
            observeCurrent(actual)
            check(!actual) { "Inline completion remained enabled after disabling it" }
        }
    }

    fun stop() {
        active = false
        val original = savedOriginal() ?: return
        observeCurrent(read())
        if (lastObserved != original) {
            pendingWrite = original
            write(original)
            observeCurrent(read())
        }
        check(lastObserved == original) { "Unable to restore inline completion preference" }
        saveOriginal(null)
    }

    private fun observeCurrent(value: Boolean) {
        // Keep provenance until read-back succeeds, including when a setter applied a value but
        // threw, or a read failed. A subsequent successful read must not call that a user action.
        val type = if (pendingWrite == value) SettingChangeType.RECORDER else SettingChangeType.USER
        pendingWrite = null
        if (lastObserved != value) {
            lastObserved = value
            changed(SettingChange(type, value))
        }
    }
}
