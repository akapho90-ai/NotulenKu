# Live Capture Migration Roadmap

## Goal
Enable live transcription while retaining a reliable original recording and the current post-recording pipeline as a fallback. This document is a plan, not an assertion that streaming capture is implemented.

## Current constraint
MeetingRecordingService uses MediaRecorder to write AAC/M4A segments and relies on journaled segment paths for recovery. MediaRecorder does not provide the PCM frame stream needed by a streaming STT client. Adding a second microphone reader is not a safe assumption: Android audio capture arbitration and OEM behavior must be tested.

## Experimental orchestration added\n`ExperimentalLiveCaptureSession` now orchestrates the existing experimental AudioRecord engine, bounded live-transcription adapter and worker in a standalone class. It is not wired into `MeetingRecordingService` and does not persist PCM audio. This is a plumbing prototype only, not a safe production recording path. If capture startup fails, the transcription worker is stopped. Capture errors are surfaced to the experimental session status callback.\n\n## Experimental PCM persistence prototype added
`ExperimentalPcmSegmentWriter` writes captured PCM16 mono bytes to an append-only experimental file and maintains a lightweight checkpoint sidecar with session, format, sample rate and byte count. `ExperimentalLiveCaptureSession` now feeds each callback's PCM into this writer and then into the live queue. The writer flushes periodically (roughly one second of audio by default) and on close. These raw PCM files are experimental, can consume substantial storage, and are not final M4A recordings or a recovery-complete production system. Write failures surface as degraded status; capture currently does not automatically terminate on a storage failure, so this prototype is not suitable for unattended production use.

## Proposed controlled migration
### Stage A — Instrument and establish invariants
- Keep current MediaRecorder path untouched.
- Record device/API, audio route, recorder start/stop failures and segment validation diagnostics (avoid audio content or secrets in logs).
- Build device test matrix: Android API levels, internal mic, wired headset, Bluetooth, interruptions, background, pause/resume and low storage.
- Treat recording integrity and journal recovery as the primary invariant.

### Stage B — Prototype single-source capture behind a disabled feature flag
Evaluate a capture adapter based on AudioRecord that reads PCM frames once and fans them out to:
1. a local durable audio writer (the recording source of truth), and
2. the live transcription frame queue.

Do not run AudioRecord concurrently with MediaRecorder for the same microphone unless explicit device tests demonstrate safe behavior. The prototype must implement buffering, disk backpressure, interruption/pause/resume and recovery before replacing the existing path.

### Stage C — Durable audio and format strategy
- Define PCM format (sample rate, mono/stereo, encoding) through negotiated capture configuration.
- Write durable PCM segments with metadata/journal checkpoints.
- Use an explicit encoder/muxer pipeline for the app's expected recording/export format; validate playable output and duration.
- Preserve original capture segments on every conversion or merge failure. Delete source segments only after output validation and journal update.
- Provide a migration/recovery strategy for partial files and app/process death.

### Stage D — Provider integration
- Implement streaming and windowed providers only against verified provider docs and auth requirements.
- Bound queue capacity and define overflow behavior; STT failure must not stop local recording.
- Persist committed transcript events and reconcile partial updates by stable window/segment IDs and timestamps.
- Keep provider credentials out of the APK where possible.

### Stage E — UI and rollout
- Add live transcript/status UI behind the feature flag.
- Clearly show provisional vs committed text and STT outage independently of recording state.
- Keep post-recording transcription as fallback/catch-up.
- Roll out only after device tests and measurable latency, battery, memory, audio-integrity and recovery criteria pass.

## Acceptance gates
- No recording data loss across pause, resume, rotation, interruption or process-recovery tests.
- No gaps or duplicate audio introduced by capture/window boundaries.
- Queue memory remains bounded under sustained provider outage.
- Original audio remains available when STT fails.
- Live latency measured on actual supported provider/device configurations.
- All claims of streaming are based on verified provider protocol and end-to-end device testing.
