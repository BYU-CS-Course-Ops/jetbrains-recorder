package recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingPluginGuardTest {
    private class Fixture {
        val enabled = mutableSetOf<String>()
        val disabledIds = mutableSetOf<String>()
        val prompts = mutableListOf<List<RecordingPlugin>>()
        val disabled = mutableListOf<List<RecordingPlugin>>()
        val errors = mutableListOf<String>()
        val events = mutableListOf<String>()
        var accepted = false
        var saved = true
        var restartCapable = true
        val guard = RecordingPluginGuard(
            isEnabled = { it in enabled },
            isDisabled = { it in disabledIds },
            confirmDisable = { prompts.add(it); accepted },
            disable = {
                disabled.add(it)
                events.add("disable")
                if (saved) disabledIds.addAll(it.map { plugin -> plugin.id })
                saved
            },
            canRestart = { restartCapable },
            restart = { events.add("restart") },
            showError = { errors.add(it) }
        )

        fun canStart(): Boolean = guard.canStart { events.add("save resume preference") }
    }

    @Test
    fun `absent or disabled plugins allow recording without a prompt or restart`() {
        val f = Fixture()
        assertTrue(f.canStart())
        assertTrue(f.prompts.isEmpty())
        assertTrue(f.disabled.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `plugins already disabled are allowed even if the IDE still reports them loaded`() {
        val f = Fixture()
        f.enabled.addAll(RecordingPluginGuard.BLOCKED_PLUGINS.map { it.id })
        f.disabledIds.addAll(f.enabled)

        repeat(3) { assertTrue(f.canStart()) }

        assertTrue(f.prompts.isEmpty())
        assertTrue(f.disabled.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `disabled Full Line does not hide an enabled Air plugin`() {
        val f = Fixture()
        f.enabled.addAll(RecordingPluginGuard.BLOCKED_PLUGINS.map { it.id })
        f.disabledIds.add("org.jetbrains.completion.full.line")
        f.accepted = true

        assertFalse(f.canStart())

        val air = listOf(RecordingPlugin("com.intellij.air", "Air"))
        assertEquals(air, f.prompts.single())
        assertEquals(air, f.disabled.single())
        assertEquals(listOf("disable", "save resume preference", "restart"), f.events)
    }

    @Test
    fun `re-enabling a previously disabled plugin restores the restart requirement`() {
        val f = Fixture()
        f.enabled.add("org.jetbrains.completion.full.line")
        f.disabledIds.addAll(f.enabled)
        assertTrue(f.canStart())

        f.disabledIds.clear()

        assertFalse(f.canStart())
        assertEquals(listOf(RecordingPlugin("org.jetbrains.completion.full.line", "Full Line Code Completion")),
            f.prompts.single())
    }

    @Test
    fun `cancel blocks recording and leaves plugins unchanged`() {
        val f = Fixture()
        f.enabled.addAll(RecordingPluginGuard.BLOCKED_PLUGINS.map { it.id })
        assertFalse(f.canStart())
        assertEquals(RecordingPluginGuard.BLOCKED_PLUGINS, f.prompts.single())
        assertTrue(f.disabled.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `each plugin is checked independently and only enabled plugins are disabled`() {
        for (plugin in RecordingPluginGuard.BLOCKED_PLUGINS) {
            val f = Fixture()
            f.enabled.add(plugin.id)
            f.accepted = true
            assertFalse(f.canStart())
            assertEquals(listOf(plugin), f.prompts.single())
            assertEquals(listOf(plugin), f.disabled.single())
            assertEquals(listOf("disable", "save resume preference", "restart"), f.events)
        }
    }

    @Test
    fun `both enabled plugins are disabled together before requesting one restart`() {
        val f = Fixture()
        f.enabled.addAll(RecordingPluginGuard.BLOCKED_PLUGINS.map { it.id })
        f.accepted = true
        assertFalse(f.canStart())
        assertEquals(f.enabled, f.disabledIds)
        assertEquals(RecordingPluginGuard.BLOCKED_PLUGINS, f.disabled.single())
        assertEquals(listOf("disable", "save resume preference", "restart"), f.events)
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun `failure to persist disabled plugins blocks recording and does not restart`() {
        val f = Fixture()
        f.enabled.add(RecordingPluginGuard.BLOCKED_PLUGINS.first().id)
        f.accepted = true
        f.saved = false
        assertFalse(f.canStart())
        assertEquals(listOf("disable"), f.events)
        assertEquals(1, f.errors.size)
    }

    @Test
    fun `unsupported automatic restart blocks recording without modifying plugins`() {
        val f = Fixture()
        f.enabled.add(RecordingPluginGuard.BLOCKED_PLUGINS.first().id)
        f.accepted = true
        f.restartCapable = false
        assertFalse(f.canStart())
        assertTrue(f.disabled.isEmpty())
        assertTrue(f.events.isEmpty())
        assertEquals(1, f.errors.size)
    }

    @Test
    fun `a plugin still loaded after disable stays blocked until restart`() {
        val f = Fixture()
        f.enabled.add(RecordingPluginGuard.BLOCKED_PLUGINS.first().id)
        f.accepted = true
        assertFalse(f.canStart())
        // A canceled IDE shutdown leaves the plugin enabled in this process.
        f.accepted = false
        assertFalse(f.canStart())
        assertEquals(2, f.prompts.size)
        // A fresh guard models relaunch; 2026.2 may still include Full Line in its loaded set.
        val afterRestart = Fixture()
        afterRestart.enabled.addAll(f.enabled)
        afterRestart.disabledIds.addAll(f.disabledIds)
        assertTrue(afterRestart.canStart())
        assertTrue(afterRestart.prompts.isEmpty())
    }

    class InlineSettings {
        var enabled = true
        fun isCompletionEnabled(): Boolean = enabled
        fun setCompletionEnabled(value: Boolean) { enabled = value }
    }

    @Test
    fun `an already disabled Full Line still has its available inline setting enforced`() {
        val f = Fixture()
        f.enabled.add("org.jetbrains.completion.full.line")
        f.disabledIds.addAll(f.enabled)
        val settings = InlineSettings()
        var original: Boolean? = null
        var warnings = 0
        var periodicCheck: () -> Unit = {}
        val inlineGuard = InlineCompletionGuard(
            savedOriginal = { original },
            saveOriginal = { original = it },
            changed = {},
            settingsProvider = { InlineCompletionSettingsAccess(settings) },
            warning = { warnings++ },
            monitor = { periodicCheck = it; AutoCloseable {} }
        )

        assertTrue(f.canStart())
        inlineGuard.start()
        try {
            assertFalse(settings.enabled)
            settings.enabled = true
            periodicCheck()
            assertFalse(settings.enabled)
        } finally {
            inlineGuard.stop()
        }
        assertTrue(settings.enabled)
        assertTrue(f.prompts.isEmpty())
        assertTrue(f.events.isEmpty())
        assertEquals(0, warnings)
    }
}
