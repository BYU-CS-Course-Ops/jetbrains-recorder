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



