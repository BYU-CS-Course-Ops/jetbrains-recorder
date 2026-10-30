# Changelog

## Unreleased

- Serialize completion checks with shutdown, ignore stale callbacks, and verify one warning per recording session; retain periodic enforcement when an IDE does not expose setting-change notifications.
- Resolve completion-setting methods from the actual service instance to handle separate plugin and content-module class loaders in PyCharm.
- Temporarily disable JetBrains language-model inline completion while recording and restore the previous preference on stop, retaining the original preference across restarts.
- Record inline completion changes as `recorderSettingChanged` for automatic changes and `userSettingChanged` for observed external changes, including changes before a file's first edit.
- Show a warning with an Inline Completion Settings button when automatic control or verification is unavailable.

## 2026-04-30.1

- Added detailed diagnostic logging for recorder startup, workspace root detection, document event queuing, recording file writes, resume prompts, focus status events, and status widget toggles.
- Improved recording write failure diagnostics, including permission errors and fallback logging behavior.
- Updated plugin and recorder metadata versions to `2026-04-30.1`.
