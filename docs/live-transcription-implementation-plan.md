# NotulenKu Live Transcription Foundation

## Current behavior confirmed in source
- Recording is handled by `MeetingRecordingService` using Android `MediaRecorder`, with a 15-minute safe rotation segment (`SEGMENT_ROTATION_SEC`).
- The existing transcription flow is post-recording: `MeetingViewModel.executeResumableJob()` prepares chunks via `AudioSegmenter.prepareAudioChunks()`, then loops over chunks sequentially and invokes `AiService.transcribeAudio()`.
- Local processing checkpoints already persist chunk status and transcript results for retry/resume.
- The built-in STT route calls backend `processAi` with task `audio_transcription`; custom provider routes call the custom STT client. This abstraction is batch-request based, not a streaming socket interface.
- The recording service journal/recovery and the current post-recording pipeline should remain intact during the first live-transcription iteration.

## Recommended staged architecture
1. Keep the original recording path as the authoritative, durable audio source.
2. Introduce a separate live-transcription session coordinator; do not put network calls or transcript state into the recorder service itself.
3. Define a provider capability interface (batch / streaming) and provider-neutral events (partial, final, error, reconnect, timing).
4. Streaming-capable providers: stream buffered audio frames through a transport, emit partial text, commit final text with timestamp metadata.
5. Providers without streaming support: fallback to rolling, finalized short audio windows. Use controlled concurrency and overlap/deduplication. Do not rotate MediaRecorder every few seconds; this risks gaps and invalid containers.
6. Persist live transcript checkpoints independently from the final meeting processing checkpoint; reconcile live transcript with the final recording before marking it final.
7. After recording ends, run a reconciliation pass on original audio (optional configurable quality pass) only for gaps/failed windows, rather than blindly re-transcribing all audio.
8. Preserve speaker labels and timestamp offsets; overlapping windows must be deduplicated.
9. Provide graceful fallback to existing post-recording pipeline when live mode is unsupported, unavailable, or interrupted.

## Proposed user experience
- Record screen shows recording duration, live transcript, and status (Listening / Transcribing / reconnecting / partial).
- Partial text is visibly provisional; finalized lines are stable.
- A pause/resume action pauses audio capture and transcription together.
- Stop recording closes the live session, flushes pending audio, finalizes transcript, then offers meeting analysis/summary.
- Failure of live transcription must not stop recording or discard audio. Offer later retry from saved audio.

## Performance goals (targets, not verified)
- First transcript text ideally appears within 3-8 seconds, provider/network dependent.
- Small audio buffers, controlled concurrency for batch fallback, exponential backoff, and a bounded queue.
- Avoid long HTTP request waits; streaming transports or short window jobs should report status and recover.
- Test 35-second, 10-minute, 1-hour and 2-hour meetings; low bandwidth, app backgrounding, interruption, pause/resume, provider timeout, and recovery.

## Decisions / prerequisites before code integration
- Select/confirm streaming STT provider(s) and their streaming API formats (WebSocket/SDK, authentication, sample rate, supported language, diarization, endpointing, billing).
- Choose audio capture strategy for live mode (PCM frame source such as AudioRecord vs MediaRecorder window fallback). Existing MediaRecorder remains for durable original recording.
- Confirm backend security architecture: avoid exposing platform secrets; provider credentials must be stored and routed securely, and user BYOK remains scoped to existing secure vault.
- Implement live mode behind a feature flag until device/provider tests pass.

## Files likely involved
- `MeetingRecordingService.kt` / a new audio-frame or segment event bridge
- New live transcription coordinator/session abstractions
- `AiService.kt` and provider client(s) for streaming capability
- `MeetingViewModel.kt` for session lifecycle, live transcript state and UI orchestration
- `RecordScreen.kt` for live transcript presentation
- Local checkpoint store for resumable live transcript state
- Tests for buffer timing, ordering, overlap deduplication, state transitions and recovery
