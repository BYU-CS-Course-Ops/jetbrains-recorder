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
