# Audio Merge Safety — Remediation Iteration 1

**Branch:** `feature/live-transcription-foundation`  
**Commit:** `e56448a51299886a332f48c63c66f00b1c03d74e`  
**Validation status:** Source change committed; runtime/build tests not executed.

## Changes
File changed: `app/src/main/java/com/example/audio/AudioSegmenter.kt`

1. **Fail closed on missing/unreadable input parts**
   - The merge no longer silently filters away missing/undersized input segments.
   - Each supplied segment is checked before merge. If a requested source is missing, too small, unreadable, lacks an audio track, or has an incompatible track format, merge returns failure rather than knowingly proceeding with an incomplete set.

2. **Fail closed during mux**
   - Exceptions during segment processing now abort the merge rather than skip a segment.
   - Each segment must produce samples; a segment that yields zero samples aborts.
   - Original segment files are not deleted by the merge function.

3. **Staged output and validation**
   - Multi-part merge is staged to a `.merging` file.
   - The staged result must pass readable-audio validation before promotion.
   - The promoted final destination is checked again.

4. **Single-segment promotion**
   - Single segment copies to a staged file and validates before promoting to the destination.

5. **Destination backup / best-effort restore**
   - Promotion retains a backup of the prior destination while attempting to place and validate the staged file.
   - On failure, restoration is attempted.
   - This is best-effort across filesystem implementations and is not a fully atomic filesystem transaction.

## Expected behavior
- Merge failures return `Result.failure` and retain source segments.
- A successful merge should have processed every supplied input segment, with staged and final outputs passing readable audio validation.
- This prevents the prior behavior where an unreadable part could be skipped and the merge still reported success.

## Remaining risks / next validation
- The validation helper confirms an audio track and at least one readable sample; it does not prove full-file decodability or exact duration/sample-count parity.
- PTS stitching currently uses the last relative sample timestamp plus a fixed 64 ms spacing; boundary/continuity testing remains necessary.
- Destination promotion/backup restoration is best-effort, not guaranteed atomic. Backup handling and directory fsync require crash-window tests.
- A source list with intentionally missing entries now fails; callers must supply a correctly enumerated list.
- No Android build, test execution, device recording, simulated filesystem failure, or crash test was executed in this commit.

## Test plan (not executed)
1. Valid 1-part merge and valid 2+ part merge.
2. Missing, zero/short, corrupt, and unreadable input segment.
3. Mixed track formats (MIME, sample rate, channels).
4. Inject/trigger read failure in later segment: verify failure and no successful incomplete output.
5. Verify prior destination survives staged copy/promotion failure.
6. Verify merge success final file plays and duration matches expected summed segment durations within codec/PTS tolerance.
7. Verify sources remain untouched after both success and failure until caller's separate cleanup policy runs.
