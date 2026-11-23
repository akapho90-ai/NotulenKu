# Remediation 1 — PCM Writer, Checkpoint & Journal Durability

Date: 2026-11-23
Scope: experimental PCM persistence foundation only. No APK build, production integration, or runtime/device validation.

## Changes
- PCM writer now retains a backup of the existing checkpoint during promotion, writes the candidate using FileOutputStream, flushes and syncs the file descriptor, verifies promoted contents, and restores the prior file if promotion/verification fails.
- Journal writes use the same backup-and-restore promotion pattern, flush and sync the candidate, and verify content before removing the backup.
- Journal listing scans primary and orphan `.bak` / `.tmp` promotion sidecars; a valid primary takes precedence, otherwise a fully parseable backup is preferred to a temp candidate. Restored candidates are promoted and sidecars are cleaned only after validation.
- PCM reopen restores a checkpoint backup or temp only when the sidecar metadata matches the session, format, sample rate, file bounds, and the journal's checkpoint byte count.
- PCM append/checkpoint operations fail closed: a caught persistence exception transitions the writer to FAILED, closes the stream best-effort, attempts to mark the journal FAILED, and blocks subsequent append calls.\n- Existing PCM files without checkpoints are rejected on reopen rather than treated as trusted data.\n- Odd-length PCM16 frames are rejected inside the append failure-handling boundary and transition the writer to FAILED.
- PCM reopen reconciliation truncation syncs the file descriptor after setLength.

## Files
- ExperimentalPcmSegmentWriter.kt
- ExperimentalPcmSessionJournal.kt

## Limitations / risk
- This is a best-effort backup/promotion scheme, not a guaranteed atomic filesystem transaction. Rename semantics and directory metadata durability vary by platform/filesystem; directory fsync is not implemented.
- The checkpoint and journal are separate sidecars; no cross-file transaction is implemented. Recovery should reconcile them and not assume all failure windows are eliminated.
- The writer's FAILED metadata update is best-effort and can itself fail.
- No Android build, unit tests, injected I/O failures, crash-window test, or physical-device test was executed in this environment. Changes were source-reviewed and committed only.

## Next validation
1. Add deterministic tests for checkpoint/journal promotion failures and restoration (basic journal backup/temp and verified checkpoint backup restoration tests have now been added).
2. Exercise flush/sync failures and ensure FAILED state prevents further appends.
3. Crash-window and filesystem-specific testing.
