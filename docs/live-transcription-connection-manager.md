# Live Transcription Connection Manager

- Bounds provider open/send/close operations with timeouts.
- Connection opening retries with exponential backoff and a configurable attempt limit.
- Transport failure during frame send triggers close/reconnect for future frames.
- The frame associated with a failed send is not resent: transport delivery may be ambiguous and replay could duplicate transcript audio.
- Session mismatch and calls after close are rejected.
- Provider failures affect only this isolated transcription pipeline.

The manager is an abstraction with fake-provider tests, not a verified implementation of any STT vendor protocol. No live credentials or endpoints are configured.
- Operation timeouts are treated as recoverable transport failures; coroutine cancellation initiated by the caller is still propagated.
- Open timeouts participate in the configured retry/backoff policy and report fallback after retry exhaustion.
- Send timeouts trigger recovery for subsequent frames, but the timed-out frame is not replayed.
- Unit tests cover open timeout recovery and send timeout reconnect behavior (tests have not been executed in this environment).
