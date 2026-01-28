package status

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import recorder.DocumentRecordedListener
import recorder.EditorRecordingManager
import recorder.RecordingStateListener
import java.awt.Color
import java.awt.Component
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

class RecordingStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "record-editor-status-widget"

    override fun getDisplayName(): String = "Recorder Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = RecordingStatusWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

private class RecordingStatusWidget(private val project: Project) :
    CustomStatusBarWidget,
    StatusBarWidget.Multiframe,
    RecordingStateListener,
    DocumentRecordedListener {

    private val panel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(0, 8, 0, 8)
        isOpaque = false
        alignmentY = Component.CENTER_ALIGNMENT
    }
    private val indicatorDot = JLabel("\u25CF")
    private val indicatorLabel = JLabel()
    private val toggleLink = ActionLink("Start") { toggleRecording() }
    private var statusBar: StatusBar? = null
    private var connection: MessageBusConnection? = null
    private var isRecording: Boolean = false
    private var flashTimer: Timer? = null
    private var isFlashing: Boolean = false

    init {
        indicatorDot.border = JBUI.Borders.emptyRight(4)
        indicatorDot.alignmentY = Component.CENTER_ALIGNMENT
        indicatorLabel.alignmentY = Component.CENTER_ALIGNMENT
        toggleLink.alignmentY = Component.CENTER_ALIGNMENT
        panel.add(indicatorDot)
        panel.add(Box.createHorizontalStrut(6))
        panel.add(indicatorLabel)
        panel.add(Box.createHorizontalStrut(8))
        panel.add(toggleLink)
        updateUi(service<EditorRecordingManager>().isRecording())
        subscribeToRecordingUpdates()
    }

    override fun ID(): String = "record-editor-status-widget"

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        flashTimer?.stop()
        flashTimer = null
        connection?.disconnect()
        connection = null
        statusBar = null
    }

    override fun copy(): StatusBarWidget = RecordingStatusWidget(project)

    override fun getComponent(): JComponent = panel

    override fun recordingStateChanged(isRecording: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            updateUi(isRecording)
        }
    }

    override fun documentChangeRecorded() {
        if (isRecording && !isFlashing) {
            ApplicationManager.getApplication().invokeLater {
                flashIndicator()
            }
        }
    }

    private fun flashIndicator() {
        if (isFlashing) return
        isFlashing = true

        // Flash to bright green
        indicatorDot.foreground = FLASH_COLOR

        // Reset timer if it exists
        flashTimer?.stop()

        // Create timer to restore normal color after a short delay
        flashTimer = Timer(150) {
            indicatorDot.foreground = if (isRecording) ACTIVE_COLOR else INACTIVE_COLOR
            isFlashing = false
            flashTimer?.stop()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun subscribeToRecordingUpdates() {
        connection = ApplicationManager.getApplication().messageBus.connect(this).also { bus ->
            bus.subscribe(EditorRecordingManager.RECORDING_STATE_TOPIC, this)
            bus.subscribe(EditorRecordingManager.DOCUMENT_RECORDED_TOPIC, this)
        }
    }

    private fun updateUi(isRecording: Boolean) {
        this.isRecording = isRecording
        val managerStateText = if (isRecording) "Recorder: On" else "Recorder: Off"
        indicatorLabel.text = managerStateText
        indicatorDot.foreground = if (isRecording) ACTIVE_COLOR else INACTIVE_COLOR
        toggleLink.text = if (isRecording) "Stop" else "Start"
        panel.toolTipText = if (isRecording) "Editor recorder is running" else "Editor recorder is stopped"
        statusBar?.updateWidget(ID())
    }

    private fun toggleRecording() {
        val manager = service<EditorRecordingManager>()
        if (manager.isRecording()) {
            manager.stopRecording()
        } else {
            manager.startRecording()
        }
    }

    companion object {
        private val ACTIVE_COLOR = JBColor(Color(0x2E7D32), Color(0x81C784))
        private val INACTIVE_COLOR = JBColor(Color(0xB71C1C), Color(0xEF9A9A))
        private val FLASH_COLOR = JBColor(Color(0x00E676), Color(0x69F0AE)) // Bright green flash
    }
}
