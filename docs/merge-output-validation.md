# Merge output validation

The segment recovery path now validates the candidate output with MediaExtractor: it must open, contain an audio track, and yield at least one readable audio sample.

Validation is applied to:
- the single-segment copy/recovery path
- the temporary multi-segment merge output before the existing output file is replaced

The merge operation returns failure when the output fails this check, allowing the stop/recovery flows to preserve original segments rather than treating a non-empty file as recovered audio.

This is a container/sample-level validation, not a full playback, duration, or acoustic-quality test. Device tests remain required.
