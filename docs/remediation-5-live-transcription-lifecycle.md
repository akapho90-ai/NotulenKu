# Remediation 5 — Experimental Live Transcription Lifecycle

## Scope
Changes on `feature/live-transcription-foundation` harden the isolated experimental AudioRecord-to-live-STT lifecycle. This remains disconnected from the production MeetingRecordingService / MediaRecorder path.

## Changes
- PCM append and adapter failures are now propagated out of the capture callback, causing the capture engine to stop instead of repeatedly continuing against a failed persistence path.
- The capture engine synchronizes start/stop operations, checks for an unreaped prior thread before restart, reports errors without allowing an error callback to throw, and attempts to stop/release AudioRecord when callback/read failures occur.
- Session startup now checks the transcription worker start result and unwinds the PCM writer on failures.
- Session shutdown remains limited to its experimental capture, worker, adapter, and writer.
- Worker drop-status updates are emitted only when the dropped-frame counter increases, avoiding repeated identical status writes.

## Safety boundaries / limitations
- This code is experimental and not integrated into the production recording path.
- No Android build, instrumentation test, device microphone test, provider integration test, or stress/race test was executed as part of this source-only change.
- AudioRecord behavior and release/read races vary by Android device and API level and require device validation.
- Provider callback behavior, cancellation and reconnect timing, and transcript correctness still require test coverage.
- Queue drops are surfaced; failed frames are not replayed automatically.
- The capture engine may still require further lifecycle redesign based on device testing; this remediation is not a certification of reliability.

## Validation
Source review and GitHub source edits only. Automated execution remains pending.
