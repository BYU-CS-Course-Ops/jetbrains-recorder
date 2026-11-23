# Changelog

## 2026-11-23.2

- Check Air (`com.intellij.air`) and Full Line Code Completion (`org.jetbrains.completion.full.line`) before every manual or automatic recording start; absent or disabled plugins do not block recording.
- Show a modal listing enabled plugins with Cancel and Disable and Restart buttons. Cancel leaves recording stopped; Disable and Restart persists plugin changes and resumes recording after relaunch.
- Respect saved disabled plugin state even when PyCharm still includes the plugin in its loaded plugin list, preventing repeated restart prompts for already disabled plugins on 2026.2.
- Keep recording stopped until restart after the recorder disables plugins, including when IDE shutdown is canceled, and ignore duplicate startup requests while the modal is open.
- Continue turning off and monitoring available inline-completion settings independently of whether Full Line is already disabled, restoring the previous preference when recording stops.
- Silently skip inline-completion settings lookup when the plugin, service, or setting API is unavailable; continue recording without displaying the settings warning.
- Retain one warning per recording session for failures controlling or verifying an available inline-completion setting.
- Set the plugin and recorded-event metadata versions to `2026-11-23.2`.

## 2026-11-04.1

- Serialize completion checks with shutdown, ignore stale callbacks, and verify one warning per recording session; retain periodic enforcement when an IDE does not expose setting-change notifications.
- Resolve completion-setting methods from the actual service instance to handle separate plugin and content-module class loaders in PyCharm.
- Temporarily disable JetBrains language-model inline completion while recording and restore the previous preference on stop, retaining the original preference across restarts.
- Record inline completion changes as `recorderSettingChanged` for automatic changes and `userSettingChanged` for observed external changes, including changes before a file's first edit.
- Show a warning with an Inline Completion Settings button when automatic control or verification is unavailable.
- Updated plugin and recorder metadata versions to `2026-11-04.1`.

## 2026-04-30.1

- Added detailed diagnostic logging for recorder startup, workspace root detection, document event queuing, recording file writes, resume prompts, focus status events, and status widget toggles.
- Improved recording write failure diagnostics, including permission errors and fallback logging behavior.
- Updated plugin and recorder metadata versions to `2026-04-30.1`.
