# Live Transcription Technical Design (Iteration 1)

## Implementation boundary
This iteration must not alter the existing MediaRecorder journaling, recovery, segment rotation, or meeting-save pipeline. Live transcript is an independent subsystem and recording remains the source of truth.

## Capture
A streaming STT client needs audio frames during capture. The current recorder path is MediaRecorder file output, which does not expose PCM frames. Do not repeatedly stop/restart MediaRecorder to manufacture small clips. For live mode, evaluate an AudioRecord PCM capture path (foreground service, audio focus, permission, device interruptions, buffer sizing, sample-rate negotiation) while preserving a durable recording path. A dual-capture implementation must be validated for audio contention/device compatibility before rollout.

## Provider contract
Define capability metadata and interfaces:
- mode: STREAMING or WINDOWED_BATCH
- supported sample rate/channel/encoding
- start(session options), sendAudio(frame), flush(), close()
- events: partial(text, stable-prefix, time range), final(segment), providerError, reconnecting, closed
- optional speaker labels/diarization, language, timestamps

Do not hard-code an unverified provider WebSocket URL, message schema, auth, or sample-rate requirements. Provider documentation/API key scope must be verified first. Existing backend processAi batch API is not assumed to support streaming.

## Session state
A session state machine should include idle, starting, listening, transcribing, reconnecting, stopping, completed, failed. Capture should continue and original recording be protected if STT is unavailable. Avoid unbounded buffering; bound queues and persist backlog metadata, then offer catch-up batch processing when needed.

## Transcript storage
Use stable IDs and source time ranges. Store provisional text separately from committed text. Only committed text is checkpointed as durable final transcript; provider partial updates replace the provisional display, never append repeatedly. Persist committed events periodically and at stop. Recovery should replay pending windows/events without duplicate insertion.

## Windowed batch fallback
Only use finalized audio windows; windowed capture/encoding strategy must avoid gaps. Concurrency should be bounded, ordering reconciled by timestamps, overlap deduplicated. Partial experience may be delayed compared with true streaming. If current recorder cannot safely produce windows, this fallback should not be falsely advertised as live until capture strategy is implemented.

## UI
Show recording duration and a separate live-STT status. Render partial text as provisional; committed lines are stable. Clearly distinguish transcription outage from recording outage. Stopping recording flushes pending audio and marks transcript incomplete if work remains.

## Security
Never ship privileged backend secrets in APK. Provider secrets should remain in server-side routing or existing protected BYOK handling. No audio is uploaded without clear user consent and provider selection/recording privacy disclosures.

## Validation
Test Android device variations, interruptions, bluetooth routing, headset, background service, pause/resume, network loss, reconnect, app crash, provider timeout/rate limit, 35 sec, 10 min, 1h and 2h recordings. Verify audio integrity, transcript ordering, duplicate handling, latency, battery, memory and retry behavior.

## Dependencies before implementation
- Verify actual streaming API availability, protocol and auth for each provider to be supported.
- Select capture strategy and validate microphone resource contention.
- Add feature flag; default disabled until validated.


## Implementation progress (Nov 2026)
- Added UI-neutral `LiveTranscriptionStatus`, `LiveTranscriptEntry`, and `LiveTranscriptionState` model types.
- Added `LiveTranscriptRepository` to hold session state and replace provisional entries by stable `windowId`, order by timestamps, and finalize windows.
- This is foundation code only; no microphone frame capture, provider websocket/streaming connection, or live UI binding is implemented yet. Do not enable or describe this as live transcription in the app until those parts are implemented and validated.


## Provider boundary and queue foundation progress
- Added provider-neutral interface and mode enum (`STREAMING`, `WINDOWED_BATCH`, `BATCH_ONLY`), frame envelope, and callback contract.
- Added bounded in-memory audio-frame queue with FIFO operations and explicit offer rejection when full.
- Added unit test for capacity and FIFO behavior.
- Still not connected to capture: the existing `MediaRecorder` and potential `AudioRecord` capture coexistence is device/API-sensitive, and simultaneous mic capture can be restricted or produce silenced capture. This must be device-validated before selecting the capture architecture.
