# Recording Schema

This repository emits the shared recording schema consumed by
`code_recorder_processor`.

## File Format

- Filename: `{basename}.recording.jsonl.gz`
- Location: adjacent to the recorded source file
- Encoding: gzip-compressed UTF-8 JSON Lines

## Edit Event

```json
{
  "type": "edit",
  "editor": "jetbrains",
  "recorderVersion": "2026-04-02.1",
  "timestamp": "2026-04-02T04:07:00.902773100Z",
  "document": "/absolute/path/to/file.py",
  "offset": 42,
  "oldFragment": "deleted text here",
  "newFragment": "inserted text here"
}
```

Required fields:

- `type`
- `timestamp`
- `document`
- `offset`
- `oldFragment`
- `newFragment`

Recorder metadata:

- `editor`
- `recorderVersion`

## Snapshot Event

Snapshots are encoded as edit events with:

- `offset = 0`
- `oldFragment === newFragment`

The recorder emits a snapshot instead of a delta when it cannot prove that the
delta replays cleanly against its tracked document state.

## Status Event

```json
{
  "type": "focusStatus",
  "editor": "jetbrains",
  "recorderVersion": "2026-04-02.1",
  "timestamp": "2026-04-02T04:07:00.902773100Z",
  "focused": true
}
```

## Compatibility

Older recordings may omit `recorderVersion`. The processor remains compatible
with those files.

## Setting Change Events

```json
{
  "type": "recorderSettingChanged",
  "editor": "jetbrains",
  "recorderVersion": "2026-04-30.1",
  "timestamp": "2026-10-28T18:00:00Z",
  "setting": "inlineCompletion.enabled",
  "value": false
}
```

Two event types distinguish the source of an observed value change:

- `recorderSettingChanged`: the recorder disabled completion at startup, enforced
  the disabled value after an external change, or restored the original value on stop.
- `userSettingChanged`: the recorder observed a value change it did not make,
  such as a user enabling completion while recording. The IDE does not identify
  the actor, so another plugin or an IDE migration can also produce this type.

For example, a user enabling completion during recording produces
`userSettingChanged` with `value: true`, followed by `recorderSettingChanged`
with `value: false` when the recorder disables it again. Both types have the same
fields shown above. Earlier development builds used the single `settingChanged`
type, which did not identify the source; new recordings use the two types above.

`setting` identifies the changed preference; `value` is its new JSON value (a
boolean for `inlineCompletion.enabled`). Currently the recorder monitors the
JetBrains language-model inline completion enable switch, not arbitrary IDE
settings or third-party completion plugins. Events describe applied, observed
value changes, rather than clicks in an unapplied settings dialog.
No change event is emitted when the value is already the requested value.

Setting changes are written to every file edited during the recording session.
Files first edited later also receive earlier setting changes with their original
timestamps. As with initial snapshots, settings events alone do not create a
recording for an untouched file. Consumers should use timestamps when combining
events, since earlier setting history can precede a file's first edit.
