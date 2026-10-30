package recorder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InlineCompletionSettingsAccessTest {
    class SettingsFixture {
        private var enabled = true
        private val updates = emptyFlow<Unit>()

        fun isCompletionEnabled(): Boolean = enabled
        fun setCompletionEnabled(value: Boolean) { enabled = value }
        fun getUpdateFlow(): Flow<Unit> = updates
    }

    @Test
    fun `methods use the actual service class when another loader defines the same name`() {
        val lookupClass = SettingsFixture::class.java
        val resource = "/${lookupClass.name.replace('.', '/')}.class"
        val bytes = checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }
        val loader = object : ClassLoader(lookupClass.classLoader) {
            fun defineServiceClass(): Class<*> = defineClass(lookupClass.name, bytes, 0, bytes.size)
        }
        val serviceClass = loader.defineServiceClass()
        val service = serviceClass.getConstructor().newInstance()
        assertNotSame(lookupClass, serviceClass)
        // Reproduce the receiver mismatch reported by modular PyCharm before testing the fix.
        assertFailsWith<IllegalArgumentException> {
            lookupClass.getMethod("isCompletionEnabled").invoke(service)
        }

        val access = InlineCompletionSettingsAccess(service)
        assertTrue(access.read())
        access.write(false)
        assertFalse(access.read())
        access.write(true)
        assertTrue(access.read())
        assertSame(serviceClass.getMethod("getUpdateFlow").invoke(service), access.updates)
    }

    @Test
    fun `missing settings API is rejected for the guard to report as unsupported`() {
        assertFailsWith<NoSuchMethodException> { InlineCompletionSettingsAccess(Any()) }
    }
}
