# NotulenKu — Expanded Static Audit Findings (Iteration 1)

Date: 2026-11-23  
Target: PR #1 / branch `feature/live-transcription-foundation`  
Scope inspected: experimental PCM/live STT source files, tests, recovery components and `AudioSegmenter.mergeM4aSegments` / chunk planner.  
Review type: source inspection only. Build, test execution, and Android device validation were not performed.

## Verdict

**Not ready for production integration.** The separation of experimental capture from the current recorder and the bounded STT queue are useful design decisions. The current implementation nevertheless has reliability risks, some potentially data-loss-adjacent, and important test gaps.

Severity labels here describe code-review priority, not runtime-confirmed exploitability.

## A. High-priority findings

### A1 — PCM writer/checkpoint/journal durability is not transactional
Files: `ExperimentalPcmSegmentWriter.kt`, `ExperimentalPcmSessionJournal.kt`

Both checkpoint and journal replacement follow a pattern that deletes the previous file before rename, with a copy fallback. If replacement fails in the gap after deleting the old file, the previous valid metadata can be lost. The copy fallback is not atomic and is not clearly verified or synced.

The writer's `append` writes bytes into a buffered stream and increments its counters before checkpoint update; checkpoint failures can propagate to capture callback handling while the stream/counter state may already have advanced. The session capture callback catches broad failures and reports degraded status, but does not terminate or safely isolate the writer failure. Further writes may continue in an uncertain state.

**Recommended tests/fixes:** retain previous metadata until replacement is verified; atomic replace where supported; sync file before replace; reconcile sidecar and journal; on persistence failure transition capture/writer to a defined failed state rather than continuing to claim recoverability. Inject write, sync, rename, copy and disk-full failures.

### A2 — Recovery pipeline is not transactionally staged and lacks a lock
Files: `ExperimentalPcmRecoveryPipeline.kt`, executor and converter

Outputs are created at deterministic locations. Pipeline failure after PCM recovery or WAV conversion can leave partial artifacts. There is no operation-level lock preventing multiple pipeline instances from racing on the same session or output directory. Preflight collision checks alone are not locking.

**Recommended:** scoped lock, private staging workspace, transaction manifest, commit only after all validations and report persistence succeed; cleanup only artifacts owned by the current transaction. Design crash recovery for stale staging directories.

### A3 — Cleanup plan is vulnerable to same-size replacement
File: `ExperimentalPcmRecoveryArtifactLifecycleManager.kt`

Cleanup revalidates canonical path, classification and size. A recognized artifact replaced with another file of the same size can still match those checks. Consider storing a hash (and/or stronger file identity where available) and revalidating it immediately before deletion. Revisit TOCTOU race limitations.

### A4 — Merge output replacement is not reliably atomic or preservation-safe
File: `AudioSegmenter.kt`, `mergeM4aSegments`

The reviewed code deletes an existing output before rename/copy fallback. If replacement copy/rename fails, the pre-existing output may be lost or replaced with an unvalidated result. The copy fallback should be verified and promoted using a safer replacement policy, preserving the existing output until the candidate is validated and successfully committed.

### A5 — Merge silently skips segments after muxer start
File: `AudioSegmenter.kt`

During multi-segment merging, segment-level exceptions are caught and logged, then processing continues. The final output can be accepted after a segment was skipped, if the output passes basic readable-audio validation. This could yield missing audio without an explicit failure result or detailed manifest of included/excluded segments.

**Recommended:** define whether partial merge is allowed; otherwise fail the merge and preserve all originals. If partial merge is allowed, expose exact skipped segment list and mark output partial, rather than returning an ordinary success.

## B. Medium-priority findings

### B1 — Merge timestamp offset uses last observed relative sample timestamp
File: `AudioSegmenter.kt`

The offset advances by `lastSegPtsUs + 64_000` per segment. This is based on the last sample timestamp rather than an independently measured segment duration/next-sample delta or verified duration. Timestamp correctness should be tested with multiple real AAC segments, gaps, variable duration, empty/failed segments and device-produced files. The fixed 64ms spacing is not demonstrated to be universally correct.

### B2 — Audio chunk output naming/reuse behavior needs scrutiny
File: `AudioSegmenter.kt`

Chunk files use deterministic names in a cache directory and existing destination files are deleted in extraction. A stale or concurrent chunking operation for the same session could conflict. Add session operation locking and unique staging files, plus cleanup/ownership tests.

### B3 — PCM frame timestamps are estimated from first read plus frame sequence
File: `PcmFrameAssembler.kt`

Frame timestamp is `sessionStartTimestamp + sequence * frameDuration`, not derived from each read timestamp after the first. This avoids jitter but does not model capture clock drift or interruptions. If actual timestamps are needed, capture timing and discontinuity semantics should be defined and tested.

### B4 — Queue overflow silently discards frames from live STT
Files: `LiveTranscriptionBuffer.kt`, adapter/worker

Queue is bounded and non-blocking (good for recorder safety), but dropped frames degrade transcription quality. The worker reports degraded status when the cumulative dropped count is >0; this status can be repeated every frame and no recovery/reconciliation policy is present. Ensure drop counts are visible, status is not noisy, and post-recording transcription is the intended recovery path.

### B5 — Connection manager reconnects after ambiguous frame failure
File: `LiveTranscriptionConnectionManager.kt`

The code intentionally does not resend a frame after timeout/transport exception, avoiding duplicate delivery. This can lose a frame (or several if connection behavior is uncertain). This is a reasonable trade-off for a prototype but requires a provider-specific acknowledgment/sequence protocol or explicit gap marker if reliable live transcription is required. Reconnect behavior also needs cancellation and concurrent close/connect tests.

### B6 — Experimental capture stop may not prove capture thread fully terminated
File: `AudioRecordCaptureEngine.kt`

Stop joins the capture thread for only 500ms and then returns. Thread termination/release races and callback activity after stop should be tested on devices. Avoid starting a second capture instance until the old thread is confirmed stopped or lifecycle state is explicitly guarded.

### B7 — PCM callback failure is not treated as a durable-recording fatal state
Files: `ExperimentalLiveCaptureSession.kt`, writer and capture engine

The capture callback reports a PCM persistence error as degraded, but the capture loop continues. Since this experimental session's writer is its local persistence path, the session must not report an ongoing recoverable recording after durable writes have failed. The production MediaRecorder path is separate, but this experimental path needs an explicit policy: stop capture, mark journal FAILED, or clearly indicate capture is no longer being safely persisted.

## C. Test coverage gaps identified

Tests exist for several basic cases (confirmation, selected collisions, clean conversion, queue boundedness, transcript state, reconnect retry and basic audit roundtrip). The following are not demonstrated by the inspected tests:

- writer/journal failure injection, checkpoint replacement crash windows and append failure;
- multi-instance and multi-process concurrency;
- pipeline rollback/staging ownership;
- cleanup same-size replacement/hash revalidation;
- merge segment omission, timestamp continuity, partial merge reporting, output preservation and copy/rename failures;
- stale chunk files, parallel chunk extraction and interrupted extraction;
- thread termination, callback-after-stop and start-stop-start races;
- STT queue sustained saturation, status storm and gap reconciliation;
- cancellation during provider open/send/close, close racing reconnect, repeated start/stop;
- malformed/transcript callbacks with wrong session/window IDs;
- full Android build and test suite execution.

## D. Review boundaries

- This audit does not establish that any issue is occurring on an actual Android device.
- Android filesystem atomicity and MediaMuxer/MediaExtractor behavior vary with implementation/device.
- Tests visible in source were not run.
- No APK was built.
- No production recorder integration was changed.

## E. Suggested remediation order

1. Obtain a reproducible Gradle build/test environment and execute the existing tests.
2. Fix durable writer/checkpoint/journal transaction semantics and define writer-failure behavior.
3. Fix merge replacement preservation and partial-segment reporting.
4. Add recovery pipeline staging/locking/rollback.
5. Strengthen cleanup plan identity.
6. Add fault-injection and race tests.
7. Test on Android devices; only then revisit production integration.

