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
`RECORDING_FORMAT.md`. Unsupported APIs and failed verification produce one sticky
warning per recording session with a button opening the Inline Completion settings
page. These failures do not stop code recording. This controls JetBrains'
language-model completion switch, not third-party completion plugins.

Validation includes headless platform tests against IntelliJ 2025.1 and a sandbox
smoke tests of the real settings bridge in PyCharm 2026.2.3 and 2026.2.4 (disable, re-enable
detection, and restoration of both original values). The plugin verifier reports
compatibility with PyCharm 2026.2.3, with an expected internal-API warning for
plugin lookup. Other IDE versions use the manual-settings warning if the bridge
cannot verify the setting.
