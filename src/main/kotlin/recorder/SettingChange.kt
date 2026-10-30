package recorder

internal enum class SettingChangeType(val eventType: String) {
    RECORDER("recorderSettingChanged"),
    USER("userSettingChanged")
}

internal data class SettingChange(val type: SettingChangeType, val value: Boolean)
