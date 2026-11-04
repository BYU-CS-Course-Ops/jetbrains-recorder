package recorder

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineCompletionGuardTest {
    // A settings API without notifications models IDE versions where only polling is available.
    class Settings {
        @Volatile var enabled = true
        var beforeRead: () -> Unit = {}
        fun isCompletionEnabled(): Boolean {
            val value = enabled
            beforeRead()
            return value
        }
        fun setCompletionEnabled(value: Boolean) { enabled = value }
    }

    private class Fixture {
        val settings = Settings()
        var saved: Boolean? = null
        var warnings = 0
        var unavailable = false
        var closes = 0
        val checks = mutableListOf<() -> Unit>()
        val changes = mutableListOf<SettingChange>()
        val guard = InlineCompletionGuard(
            savedOriginal = { saved },
            saveOriginal = { saved = it },
            changed = { changes.add(it) },
            settingsProvider = {
                check(!unavailable) { "Unsupported IDE settings API" }
                InlineCompletionSettingsAccess(settings)
            },
            warning = { warnings++ },
            monitor = { check ->
                checks.add(check)
                AutoCloseable { closes++ }
            }
        )
    }

    @Test
    fun `repeated unsupported API checks warn only once until next recording starts`() {
        val f = Fixture()
        f.unavailable = true
        f.guard.start()
        repeat(10) { f.checks.single().invoke() }
        f.guard.start() // Duplicate start must not reset the warning gate or add a monitor.
        assertEquals(1, f.warnings)
        assertEquals(1, f.checks.size)
        assertNull(f.saved)
        f.guard.stop()
        f.guard.start()
        assertEquals(2, f.warnings)
        f.guard.stop()
        assertEquals(2, f.closes)
    }

    @Test
    fun `periodic checks enforce and restore the preference without change notifications`() {
        val f = Fixture()
        f.guard.start()
        assertFalse(f.settings.enabled)
        f.settings.enabled = true
        f.checks.single().invoke()
        assertFalse(f.settings.enabled)
        f.guard.stop()
        assertTrue(f.settings.enabled)
        assertEquals(
            listOf(
                SettingChange(SettingChangeType.RECORDER, false),
                SettingChange(SettingChangeType.USER, true),
                SettingChange(SettingChangeType.RECORDER, false),
                SettingChange(SettingChangeType.RECORDER, true)
            ),
            f.changes
        )
        assertEquals(0, f.warnings)
    }

    @Test
    fun `queued checks from stopped sessions cannot change the preference or warn`() {
        val f = Fixture()
        f.guard.start()
        val oldCheck = f.checks.single()
        f.guard.stop()
        f.unavailable = true
        oldCheck()
        assertTrue(f.settings.enabled)
        assertEquals(0, f.warnings)
        f.unavailable = false
        f.guard.start()
        f.settings.enabled = true
        oldCheck()
        assertTrue(f.settings.enabled)
        f.checks.last().invoke()
        assertFalse(f.settings.enabled)
        f.guard.stop()
    }

    @Test
    fun `unsupported API can recover while recording without losing the original preference`() {
        val f = Fixture()
        f.unavailable = true
        f.guard.start()
        f.unavailable = false
        f.checks.single().invoke()
        assertFalse(f.settings.enabled)
        assertEquals(true, f.saved)
        f.guard.stop()
        assertTrue(f.settings.enabled)
        assertNull(f.saved)
        assertEquals(1, f.warnings)
    }

    @Test
    fun `shutdown waits for an in-flight check before restoring the original preference`() {
        val f = Fixture()
        f.guard.start()
        f.settings.enabled = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.settings.beforeRead = {
            if (Thread.currentThread().name == "recording-check") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val checkResult = CompletableFuture<Unit>()
        val checkThread = Thread({
            try {
                f.checks.single().invoke()
                checkResult.complete(Unit)
            } catch (t: Throwable) {
                checkResult.completeExceptionally(t)
            }
        }, "recording-check")
        checkThread.start()
        val stopStarted = CountDownLatch(1)
        val stopped = CompletableFuture<Unit>()
        val stopThread = Thread {
            stopStarted.countDown()
            try {
                f.guard.stop()
                stopped.complete(Unit)
            } catch (t: Throwable) {
                stopped.completeExceptionally(t)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stopThread.start()
            assertTrue(stopStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { stopped.get(100, TimeUnit.MILLISECONDS) }
        } finally {
            release.countDown()
            checkThread.join(5000)
            stopThread.join(5000)
        }
        checkResult.get(5, TimeUnit.SECONDS)
        stopped.get(5, TimeUnit.SECONDS)
        assertTrue(f.settings.enabled)
        assertNull(f.saved)
        assertEquals(0, f.warnings)
    }
}
