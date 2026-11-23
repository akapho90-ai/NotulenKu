# Recording merge recovery hardening

## Change
The merge path now probes candidate recording segments with MediaExtractor before choosing a muxer track. Non-empty files that have no readable audio track, cannot be opened, or do not match the selected audio track MIME/sample rate/channel count are skipped. This addresses the failure mode where a damaged first segment prevented later usable segments from being recovered.

## Safety boundaries
- Original source segments are not deleted by the merge operation.
- A successful merge may contain only the readable, compatible segments; skipped files can mean audio gaps.
- Existing stop-flow deletion behavior should only be relied on after the merged result is independently validated. Device-level verification is still required.
- This is recovery behavior, not a replacement for validating MediaRecorder stop/finalization.

## Validation still required
Test on a device with (1) all healthy segments, (2) an invalid first segment followed by valid segments, (3) mixed/incompatible track formats, (4) a single readable segment, and (5) all segments unreadable. Confirm duration and playback, and ensure originals are preserved until the application confirms recovery.
