package recorder

import com.intellij.ide.plugins.DisabledPluginsState
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent

/** Checks application-wide plugin prerequisites before any recording listeners or writers start. */
internal class RecordingPluginGuard(
    private val isEnabled: (String) -> Boolean = { id ->
        PluginManagerCore.loadedPlugins.any { it.pluginId.idString == id }
    },
    private val isDisabled: (String) -> Boolean = { id ->
        PluginManagerCore.isDisabled(PluginId.getId(id))
    },
    private val confirmDisable: (List<RecordingPlugin>) -> Boolean = ::showDisableDialog,
    private val disable: (List<RecordingPlugin>) -> Boolean = ::disablePlugins,
    private val canRestart: () -> Boolean = { ApplicationManager.getApplication().isRestartCapable },
    private val restart: () -> Unit = { ApplicationManager.getApplication().restart() },
    private val showError: (String) -> Unit = ::showErrorDialog
) {
    private val logger = Logger.getInstance(RecordingPluginGuard::class.java)
    private val awaitingRestart = mutableSetOf<String>()

    /**
     * Must run on the EDT. A restart never allows recording in this process: disabled plugins may
     * still be loaded until shutdown. beforeRestart preserves the recording preference for resume.
     */
    fun canStart(beforeRestart: () -> Unit): Boolean = try {
        // Some IDEs retain Full Line in the loaded plugin set even when Plugins shows it disabled.
        // Respect the saved disabled state, except for changes we just made in this process: those
        // must still complete their restart. This in-memory exception disappears on IDE relaunch.
        val enabled = BLOCKED_PLUGINS.filter {
            isEnabled(it.id) && (it.id in awaitingRestart || !isDisabled(it.id))
        }
        if (enabled.isEmpty()) {
            true
        } else {
            if (confirmDisable(enabled)) {
                if (!canRestart()) {
                    showError("The IDE cannot restart automatically. Disable the listed plugins in Settings → Plugins, " +
                        "restart the IDE manually, then start the Code Recorder again.")
                } else if (!disable(enabled)) {
                    showError("The plugin changes could not be saved. Recording has not started. " +
                        "Disable the listed plugins in Settings → Plugins and restart the IDE before trying again.")
                } else {
                    awaitingRestart.addAll(enabled.map { it.id })
                    beforeRestart()
                    restart()
                }
            }
            false
        }
    } catch (e: Exception) {
        reportFailure(e)
        false
    } catch (e: LinkageError) {
        reportFailure(e)
        false
    }

    private fun reportFailure(error: Throwable) {
        logger.warn("Unable to check or disable AI plugins before recording", error)
        showError("The Code Recorder could not check or disable the AI plugins. Recording has not started. " +
            "Check Air and Full Line Code Completion in Settings → Plugins and restart the IDE before trying again.")
    }

    companion object {
        val BLOCKED_PLUGINS = listOf(
            RecordingPlugin("com.intellij.air", "Air"),
            RecordingPlugin("org.jetbrains.completion.full.line", "Full Line Code Completion")
        )

        private fun showDisableDialog(plugins: List<RecordingPlugin>): Boolean {
            return DisablePluginsDialog(plugins).showAndGet()
        }

        private fun disablePlugins(plugins: List<RecordingPlugin>): Boolean {
            // Persist the whole set in one operation, preserving unrelated disabled plugins. This
            // deliberately does not unload plugins dynamically or change their live enabled flags.
            val ids = plugins.map { PluginId.getId(it.id) }
            return DisabledPluginsState.saveDisabledPluginsAndInvalidate(
                DisabledPluginsState.getDisabledIds() + ids
            )
        }

        private fun showErrorDialog(message: String) {
            val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
            Messages.showErrorDialog(project, message, "Code Recorder Cannot Start")
        }
    }
}

/** DialogWrapper provides a centered IDE modal, including on macOS where Messages can use sheets. */
private class DisablePluginsDialog(private val plugins: List<RecordingPlugin>) :
    DialogWrapper(null as Project?, false, IdeModalityType.IDE) {
    init {
        title = "Disable AI Plugins to Start Recording"
        setOKButtonText("Disable and Restart")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val ideName = ApplicationNamesInfo.getInstance().fullProductName
        val names = plugins.joinToString("") { "<li>${it.name}</li>" }
        return JBLabel(
            "<html><body style='width: 380px'>" +
                "The following enabled plugins must be disabled for the Code Recorder to work:" +
                "<ul>$names</ul>" +
                "Disable these plugins and restart $ideName to apply the changes.<br><br>" +
                "Recording will start after the restart. Cancel leaves recording stopped." +
                "</body></html>"
        )
    }
}

internal data class RecordingPlugin(val id: String, val name: String)
