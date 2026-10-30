package recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineCompletionPolicyTest {
    private fun recorder(value: Boolean) = SettingChange(SettingChangeType.RECORDER, value)
    private fun user(value: Boolean) = SettingChange(SettingChangeType.USER, value)

    private class Fixture(initial: Boolean, original: Boolean? = null) {
        var enabled = initial
        var saved = original
        var ignoreWrites = false
        var failReads = false
        var failReadAfterWrite = false
        val events = mutableListOf<SettingChange>()
        val policy = InlineCompletionPolicy(
            read = {
                check(!failReads) { "Settings unavailable" }
                enabled
            },
            write = {
                if (!ignoreWrites) enabled = it
                if (failReadAfterWrite) failReads = true
            },
            savedOriginal = { saved },
            saveOriginal = { saved = it },
            changed = { events.add(it) }
        )
    }

    @Test
    fun `enabled completion is disabled then restored with both changes recorded`() {
        val fixture = Fixture(true)
        fixture.policy.start()
        assertFalse(fixture.enabled)
        assertEquals(true, fixture.saved)
        fixture.policy.stop()
        assertTrue(fixture.enabled)
        assertNull(fixture.saved)
        assertEquals(listOf(recorder(false), recorder(true)), fixture.events)
    }

    @Test
    fun `previously disabled completion stays disabled without spurious records`() {
        val fixture = Fixture(false)
        fixture.policy.start()
        fixture.policy.check()
        fixture.policy.stop()
        assertFalse(fixture.enabled)
        assertNull(fixture.saved)
        assertEquals(emptyList(), fixture.events)
    }

    @Test
    fun `user enabling completion is recorded and immediately reverted`() {
        val fixture = Fixture(true)
        fixture.policy.start()
        fixture.enabled = true
        fixture.policy.check()
        fixture.policy.check()
        assertFalse(fixture.enabled)
        assertEquals(listOf(recorder(false), user(true), recorder(false)), fixture.events)
        assertEquals(true, fixture.saved)
    }

    @Test
    fun `resumed recording preserves original preference from before restart`() {
        val fixture = Fixture(false, original = true)
        fixture.policy.start()
        fixture.policy.stop()
        assertTrue(fixture.enabled)
        assertEquals(listOf(recorder(true)), fixture.events)
    }

    @Test
    fun `unsuccessful disable is detected and original preference is retained`() {
        val fixture = Fixture(true)
        fixture.ignoreWrites = true
        assertFailsWith<IllegalStateException> { fixture.policy.start() }
        assertEquals(true, fixture.saved)
        assertEquals(emptyList(), fixture.events)
        fixture.ignoreWrites = false
        fixture.policy.check()
        assertFalse(fixture.enabled)
        fixture.policy.stop()
        assertTrue(fixture.enabled)
    }

    @Test
    fun `unsuccessful restore retains original preference for recovery`() {
        val fixture = Fixture(true)
        fixture.policy.start()
        fixture.ignoreWrites = true
        assertFailsWith<IllegalStateException> { fixture.policy.stop() }
        assertEquals(true, fixture.saved)
        fixture.ignoreWrites = false
        fixture.policy.stop()
        assertTrue(fixture.enabled)
        assertNull(fixture.saved)
    }

    @Test
    fun `unavailable reads never invent an original preference and can recover`() {
        val fixture = Fixture(true)
        fixture.failReads = true
        assertFailsWith<IllegalStateException> { fixture.policy.start() }
        assertNull(fixture.saved)
        fixture.failReads = false
        fixture.policy.check()
        fixture.policy.stop()
        assertTrue(fixture.enabled)
    }

    @Test
    fun `read-back failure retains recorder provenance until a successful read`() {
        val fixture = Fixture(true)
        fixture.failReadAfterWrite = true
        assertFailsWith<IllegalStateException> { fixture.policy.start() }
        assertFalse(fixture.enabled)
        assertEquals(emptyList(), fixture.events)
        fixture.failReads = false
        fixture.failReadAfterWrite = false
        fixture.policy.check()
        assertEquals(listOf(recorder(false)), fixture.events)
        fixture.policy.stop()
        assertEquals(listOf(recorder(false), recorder(true)), fixture.events)
    }

    @Test
    fun `user change immediately before stop is distinct from restoring an originally off setting`() {
        val fixture = Fixture(false)
        fixture.policy.start()
        fixture.enabled = true
        fixture.policy.stop()
        assertEquals(listOf(user(true), recorder(false)), fixture.events)
        assertFalse(fixture.enabled)
    }

    @Test
    fun `late setting notifications after stop do not reapply recording policy`() {
        val fixture = Fixture(true)
        fixture.policy.start()
        fixture.policy.stop()
        fixture.policy.check()
        assertTrue(fixture.enabled)
        assertEquals(listOf(recorder(false), recorder(true)), fixture.events)
    }
}
