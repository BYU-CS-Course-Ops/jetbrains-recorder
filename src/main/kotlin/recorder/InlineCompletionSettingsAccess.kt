package recorder

import kotlinx.coroutines.flow.Flow

/**
 * Resolve methods on the returned service, not the lookup class. Modular IDEs can load the same
 * class name in both a plugin loader and a content-module loader; their Class objects differ.
 */
internal class InlineCompletionSettingsAccess(private val service: Any) {
    private val serviceClass = service.javaClass
    private val getter = serviceClass.getMethod("isCompletionEnabled")
    private val setter = serviceClass.getMethod("setCompletionEnabled", Boolean::class.javaPrimitiveType)
    // Change notifications are optional: periodic reads can still verify and enforce the setting
    // when an IDE version retains the preference API but no longer exposes this flow.
    val updates: Flow<*>? = try {
        serviceClass.getMethod("getUpdateFlow").invoke(service) as? Flow<*>
    } catch (_: NoSuchMethodException) {
        null
    }

    fun read(): Boolean = getter.invoke(service) as Boolean

    fun write(value: Boolean) {
        setter.invoke(service, value)
    }
}
