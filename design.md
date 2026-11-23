# Design

## Doc listener

DocChangedEvent -> queue
Other thread: queue -> batcher -> writer

Batch by:
- Document (flush if different document)
- Timestamp (flush after idle for 1 second)

The writer opens a new GZIP stream to write the provided batch.

The output location should be adjacent to the observed file:
and event in `program.py` should be written to `program.recording.jsonl.gz`

## Strategy

Avoid class variables when reasonable. Try to maintain a more functional design.

The goal is to keep the code simple and easy to reason about.

## Inline completion during recording

Before registering recording listeners or starting a writer, `RecordingPluginGuard`
checks Air (`com.intellij.air`) and Full Line Code Completion
(`org.jetbrains.completion.full.line`). Missing or already disabled plugins do not
block recording. Enabled plugins trigger a centered IDE modal listing the plugins
that must be disabled. Cancel leaves recording stopped. Disable and Restart saves
the disabled plugin IDs without changing unrelated plugin preferences, preserves
the recording resume preference, and requests an IDE restart. Failed saves leave
recording stopped. The same check applies to manual starts and automatic resume.
Duplicate start requests are ignored while the modal is open.

The check respects the saved disabled plugin state because PyCharm 2026.2 may
still include disabled Full Line in its loaded plugin list. Plugins disabled by
the recorder in the current process remain blocked until that process exits,
preventing a canceled restart from allowing recording too early. These pending
restart IDs are kept only in memory so they do not cause another restart prompt
after relaunch. Plugin disabling persists until the user re-enables the plugin;
it is independent of the temporary inline-completion preference described below.

`InlineCompletionGuard` accesses the Full Line plugin's `MLCompletionSettings`
service through reflection. This keeps the recorder loadable when the optional
plugin or its internal API is unavailable. Methods are resolved from the actual
service instance: the lookup class can come from a different class loader than
the service in modular IDE builds, even when both have the same class name.
It subscribes to the service's update
flow and verifies the setting every second as a safeguard against startup
migrations and missed notifications. Updates trigger a read of the applied value;
the notification itself does not contain the changed value.
If the settings API has no update flow, periodic verification still enforces the
preference. Checks and shutdown are serialized so a pending check cannot disable
completion after the original preference has been restored. Session identifiers
reject stale timer callbacks, flow callbacks, and queued warning popups.

`InlineCompletionPolicy` saves the initial enable value before disabling it,
reverts observed attempts to enable completion, and restores the initial value on
stop. The original value is included in the recorder's persistent state so a
resumed session does not mistake a temporary disabled preference for the original.
Disposal restores the preference too, while preserving the recorder's existing
resume preference. A failed restoration retains the original value for recovery.

Observed value changes use the `recorderSettingChanged` and `userSettingChanged` schemas documented in
`RECORDING_FORMAT.md`. If the plugin, service, or reflective settings API cannot
be found, the guard silently skips the lookup and retries on later periodic
checks. Once a setting is available, failures controlling or verifying it produce
one sticky warning per recording session with a button opening the Inline
Completion settings page. These failures do not stop code recording. Setting
control runs independently of the plugin check so an available preference is
still enforced when Full Line is already disabled. Third-party completion
plugins are outside the scope of these checks.

Validation includes headless platform tests against IntelliJ 2025.1, regression
coverage for cancellation, restart, already disabled but loaded plugins, and
silent settings lookup failures. Binary verification covers IntelliJ 2025.1 and
PyCharm 2026.3.0.1, with expected internal API notices. Earlier sandbox smoke tests
of the real settings bridge covered PyCharm 2026.2.3 and 2026.2.4 (disable,
re-enable detection, and restoration of both original values). The new modal and
restart flow still requires manual verification in PyCharm 2026.2 and 2026.3.
