# Experimental AudioRecord capture engine

## Scope
`AudioRecordCaptureEngine` is a new, isolated PCM capture primitive for the live-transcription prototype. It is intentionally not wired into `MeetingRecordingService` or the current `MediaRecorder` recording path.

## Contract
- Captures PCM16, mono, at 16 kHz by default.
- Checks the runtime RECORD_AUDIO permission before creating capture.
- Uses a bounded read buffer and copies each delivered read so downstream consumers cannot mutate the capture buffer.
- Callbacks execute on the capture thread; callers should avoid blocking or expensive work and should hand off frames to a bounded queue.
- Stops and releases the AudioRecord instance, attempting to unblock the read loop.
- Capture/callback errors are reported through the error callback.

## Safety constraints
- Do not start this alongside the production recorder: simultaneous microphone capture can contend, silence one source, or change device behavior.
- No provider/network connection is implemented here.
- The engine is not yet a production replacement for MediaRecorder. It does not persist PCM or encode AAC/M4A, and does not implement pause/resume, recovery journals, interruption policy, Bluetooth routing policy, or device-specific validation.
- Callback timestamp uses elapsed realtime at the time of the read callback, not the exact hardware capture timestamp. Frame timing should be reconciled by the frame assembler.
- Validation needed: build, unit/instrumented tests, short capture/playback checks, permission denial, repeated start/stop, interruptions, Bluetooth/headset behavior, and long-duration memory/storage behavior on physical devices.

## Next
Create an adapter that feeds the frame assembler and bounded transcription queue, then add a provider implementation only after a provider's protocol and security requirements are selected and verified. Preserve the existing recorder path throughout.
