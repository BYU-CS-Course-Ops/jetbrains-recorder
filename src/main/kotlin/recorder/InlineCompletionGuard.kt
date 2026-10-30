package recorder

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.Timer

/**
 * Optional bridge to the bundled Full Line plugin. No implementation classes are linked at load
 * time, so an absent or incompatible completion plugin cannot prevent the recorder from loading.
 * Checks normally run on the event dispatch thread. Lifecycle operations are serialized with
 * checks because application disposal can run on another thread.
 */
internal class InlineCompletionGuard(
    private val savedOriginal: () -> Boolean?,
    private val saveOriginal: (Boolean?) -> Unit,
    private val changed: (SettingChange) -> Unit,
    private val settingsProvider: () -> InlineCompletionSettingsAccess = ::findSettings,
    private val warning: (() -> Unit)? = null,
    private val monitor: (() -> Unit) -> AutoCloseable = { check ->
        val timer = Timer(1000) { check() }.apply { start() }
        AutoCloseable { timer.stop() }
    }
) {
    private val logger = Logger.getInstance(InlineCompletionGuard::class.java)
    private var policy: InlineCompletionPolicy? = null
    private var scope: CoroutineScope? = null
    private var monitoring: AutoCloseable? = null
    private var running = false
    private var warned = false
    private var generation = 0

    @Synchronized
    fun start() {
        if (running) return
        running = true
        warned = false
        generation++
        verify()
        // Also re-check after plugin initialization/migrations and recover from a transient failure.
        val sessionGeneration = generation
        monitoring = monitor { verify(sessionGeneration) }
    }

    @Synchronized
    private fun verify(expectedGeneration: Int = generation) {
        if (!running || expectedGeneration != generation) return
        attempt {
            if (policy == null) connect()
            policy?.check()
        }
    }

    private fun connect() {
        val access = settingsProvider()
        val newPolicy = InlineCompletionPolicy(
            read = access::read,
            write = access::write,
            savedOriginal = savedOriginal,
            saveOriginal = saveOriginal,
            changed = changed
        )
        val sessionGeneration = generation
        // Subscribe before changing the preference; cancellation and generation checks prevent a
        // delayed notification from a previous recording from affecting a later session.
        access.updates?.let { updates ->
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = newScope
            newScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    updates.collect {
                        ApplicationManager.getApplication().invokeLater {
                            verify(sessionGeneration)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        reportSessionFailure(e, sessionGeneration)
                    }
                }
            }
        }
        policy = newPolicy
        newPolicy.start()
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        monitoring?.close()
        monitoring = null
        scope?.cancel()
        scope = null
        attempt { policy?.stop() }
        policy = null
    }

    @Synchronized
    private fun reportSessionFailure(error: Throwable, expectedGeneration: Int) {
        if (running && generation == expectedGeneration) reportFailure(error)
    }

    private fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            reportFailure(e)
        } catch (e: LinkageError) {
            reportFailure(e)
        }
    }

    private fun reportFailure(error: Throwable) {
        if (warned) return
        warned = true
        logger.warn("Unable to control or verify inline completion settings", error)
        if (warning != null) {
            warning.invoke()
            return
        }
        // Disposal can invoke this off the EDT; do not create UI on the disposing thread.
        val sessionGeneration = generation
        ApplicationManager.getApplication().invokeLater { showWarning(sessionGeneration) }
    }

    @Synchronized
    private fun showWarning(expectedGeneration: Int) {
        if (generation != expectedGeneration) return
        if (ApplicationManager.getApplication().isUnitTestMode ||
            ApplicationManager.getApplication().isDisposed
        ) return
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeRecorder.Settings")
            .createNotification(
                "Check inline code completion settings",
                "The recorder was unable to verify whether inline code completion was turned off. " +
                    "While recording, please open Settings → Editor → General → Code Completion → Inline " +
                    "and uncheck ‘Enable inline completion using language models’. " +
                    "If recording has stopped, check and restore your preferred setting manually.",
                NotificationType.WARNING
            )
            .addAction(NotificationAction.createSimple("Open Inline Completion Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project,
                    { configurable ->
                        (configurable as? SearchableConfigurable)?.id == "editor.preferences.completion.inline"
                    },
                    null
                )
            })
            .notify(project)
    }

    companion object {
        private fun findSettings(): InlineCompletionSettingsAccess {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.completion.full.line"))
                ?: error("Full Line Code Completion plugin is unavailable")
            val loader = plugin.pluginClassLoader ?: error("Full Line plugin class loader is unavailable")
            val settingsClass = loader.loadClass(
                "com.intellij.ml.inline.completion.impl.configuration.MLCompletionSettings"
            )
            val settings = ApplicationManager.getApplication().getService(settingsClass)
                ?: error("Inline completion settings service is unavailable")
            return InlineCompletionSettingsAccess(settings)
        }
    }
}
