# AudioSegmenter merge timestamp and validation audit

## Reviewed
- Segment format compatibility checks (MIME, sample rate, channel count)
- Per-segment sample timestamp rebasing
- Merged sample count and staged output promotion
- Container/sample readability validator

## Findings
1. Merge timestamp continuity uses the final observed sample timestamp in a segment plus a fixed 64ms gap. This is conservative but does not establish exact duration parity or guarantee gapless continuity; encoded sample duration and container timing may vary.
2. `isReadableAudioFile` reads one sample only. It confirms a minimally readable container/track, not full-file decode integrity.
3. Segment source files are preserved on merge failures; merged output uses a staged path and backup/restore attempt.
4. Existing validation can still miss corruption later in a file, and promotion is not a filesystem-atomic transaction.

## Decision
No speculative timestamp algorithm change was made without Android MediaCodec/MediaMuxer device-level and fixture testing. Changing offset semantics could introduce overlap or truncation. These are recorded as residual risks and follow-up validation needs.

## Recommended next verification
- Android-device integration tests with AAC/M4A segments of known duration and different pause/rotation lengths.
- Decode entire merged output (not just first sample) and compare measured duration against sum of input durations within a codec tolerance.
- Test segment transitions, timestamp monotonicity and playback at transitions.
