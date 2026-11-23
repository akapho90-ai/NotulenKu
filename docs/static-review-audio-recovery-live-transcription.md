# NotulenKu Audio Recovery & Live Transcription — Static Review / Evaluation

Date: 2026-11-23  
Branch reviewed: `feature/live-transcription-foundation`  
Scope: changes in PR #1 related to recording merge hardening, experimental PCM capture, session journaling/integrity, recovery decision/execution, WAV conversion/readiness probe, recovery lifecycle/audit, and live transcription plumbing.

## Review type and limitations

This is a **source-level/static review** of the current branch files and the test cases authored alongside them. No APK was requested or built. The Android Gradle build, unit/Robolectric test suite, instrumentation tests, device audio tests, crash simulations, and playback tests were **not executed** in this review. Findings below are code-inspection findings or test gaps, not a runtime test report.

## Executive conclusion

The changes are directionally conservative: experimental PCM is separated from the production MediaRecorder path; recovery requires explicit confirmation; source preservation and deterministic output collision checks are intended; and the live transcription prototype is not presented as production-ready.

However, **the experimental recovery path should not be considered production-ready yet**. Several reliability gaps and discrepancies need addressing before integration or rollout. The most important are transaction/rollback behavior, concurrency protection, journal replacement durability, cleanup-plan identity, and verifying audit integration in the pipeline.

## Findings (prioritized)

### P0/P1 — Recovery pipeline transaction and concurrency
**Status: unresolved / requires follow-up**

- The pipeline preflights its deterministic WAV and pipeline-report outputs, but its work is not an end-to-end transaction. If the PCM recovery executor succeeds and a subsequent WAV conversion, validation, or report write fails, intermediate artifacts can remain. This behavior is documented, but it complicates retries and can leave a session in an ambiguous state.
- No pipeline-level lock or cross-instance/process lock was observed in the reviewed pipeline/executor/converter. Concurrent calls for the same session or recovery directory may race on deterministic output names, report files, and temporary files. Collision checks are not equivalent to concurrency control.
- The recommended next step is a staged recovery workspace plus a scoped lock and an explicit commit/rollback policy. Rollback must delete only artifacts created by the current operation and must not remove pre-existing files.

### P1 — Audit trail integration mismatch
**Status: verify and correct**

- The pipeline source reviewed here does not visibly invoke `ExperimentalPcmRecoveryAuditTrail`, although the audit-trail documentation describes pipeline lifecycle events (STARTED/COMPLETED/VALIDATION_FAILED/FAILED).
- Treat integration as unverified until the current branch is reconciled and a test demonstrates that a pipeline run writes the expected events. The audit logger itself is not a database and does not provide a cross-process lock or tamper protection.

### P1 — Recovery cleanup identity protection
**Status: unresolved risk**

- The cleanup executor rechecks canonical path, file kind, and file size against the cleanup plan. A file replaced or modified in-place with the same size and recognized name may still pass those checks. Size/name/type are insufficient identity checks for high-confidence destructive cleanup.
- Prefer a stronger plan identity (e.g., hash or additional file identity/metadata where supported), revalidation, and staged deletion. Keep explicit confirmation and preserve unknown artifacts.

### P1 — PCM journal replacement durability
**Status: unresolved risk**

- The journal write path writes a temporary file, then deletes the existing journal before renaming the temp file. This creates a window where the prior journal is absent if replacement fails.
- The fallback copy path may not be atomic. Durability should be improved with an atomic replacement strategy where supported, sync of file and directory where feasible, and a safe fallback that preserves the previous journal until the new one is verified.

### P1 — Audit file concurrency/durability
**Status: unresolved risk**

- The audit trail uses an instance-level synchronized lock and temp-file replacement. Multiple instances or processes are not coordinated, and stale temp files intentionally block writes.
- This is suitable only as a prototype unless concurrency and recovery semantics are tightened. Tests should exercise simultaneous writers, stale temp recovery policy, and persistence failure behavior.

### P2 — WAV readiness is not playback verification
**Status: correctly scoped, but acceptance language must remain careful**

- The validator probes a file with MediaExtractor, audio-track metadata, and sample readability. This cannot prove audible playback, device routing, or actual playback quality.
- The pipeline status `COMPLETED_VALID_READABLE` should be interpreted as a framework-level probe result only, not user playback success.

### P2 — Test coverage is primarily happy-path/guard coverage
**Status: tests authored, not run**

The current tests cover confirmation guards, selected collision handling, source preservation in some recovery/conversion cases, artifact inventory/cleanup, audit log roundtrip, and some decision behavior. The following important test classes are still needed:

- Recovery pipeline: failure injection after each stage; rollback ownership; concurrent same-session runs; concurrent different-session runs; stale temp workspace handling; pre-existing artifacts must remain untouched.
- Executor: short reads, disk-full/write errors, hash mismatch, temp/output/report collisions independently, rename failures.
- WAV conversion: copy failure, sync/rename failure, RIFF-size boundary, arithmetic overflow, validator rejection, source path/target path symlink/path escape cases.
- Journal: crash between temp write and replacement, replacement failure while old journal exists, malformed journal values, duplicate/unsafe session IDs, path traversal in fileName.
- Cleanup: same-size replacement after planning, symlink/path race, partial cleanup failure and retry semantics.
- Audit: multiple instances/processes, temp-file failure, malformed/truncated log lines, lifecycle integration with pipeline.
- Live transcription: queue saturation under prolonged provider outage, lifecycle cancellation races, reconnect race conditions, shutdown timeouts, frame drops and metrics, and provider contract tests using verified provider protocol.

## Recommended test sequence before implementation advances

1. Run Gradle unit/Robolectric tests and capture exact output and failures.
2. Static compilation check on the target branch (no APK packaging needed).
3. Repair compile/test failures first; do not infer success from tests being authored.
4. Add fault-injection tests for recovery/journal/audit/cleanup, especially concurrency and partial failure.
5. Run device tests on a small Android API/OEM matrix for capture, interruptions, low-storage behavior, and playback.
6. Only after gates pass, consider wiring the experimental PCM capture into a production recording flow.

## Areas that should remain isolated for now

- Do not replace or alter the current production MediaRecorder recording path with the experimental AudioRecord path until device tests confirm a single-source capture architecture.
- Live STT provider integration, UI integration, and production rollout remain gated by provider protocol verification, credentials strategy, durable audio, backpressure behavior, and device testing.
- Do not claim that the recording recovery pipeline guarantees crash-proof recovery or that the WAV probe guarantees audible playback.

## Overall assessment

**Proceed with evaluation and hardening, not rollout.** The branch has useful foundational code and test intent, but transactionality, concurrency, destructive cleanup identity, journal durability, and verified audit integration should be addressed before moving farther into production integration.