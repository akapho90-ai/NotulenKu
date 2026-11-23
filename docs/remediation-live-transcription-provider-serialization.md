# Remediation: Serialize Live Transcription Provider Lifecycle

## Finding
The connection manager previously allowed `connect()`, `sendFrame()`, and `close()` to operate concurrently. Concurrent provider calls could overlap an open, close, or send and create inconsistent connection state or frames sent to a closing transport.

## Change
Added a coroutine `Mutex` to serialize the manager's public lifecycle operations. `sendFrame()` now connects through the internal retry method while holding the same lock, avoiding recursive lock acquisition. `close()` is serialized with sends and connects. Frame delivery remains intentionally non-retrying when transport delivery is ambiguous.

## Regression tests
Added tests for serialized concurrent sends (maximum one send in-flight) and rejecting a frame after close.

## Validation
This is a source and test change. CI is required to confirm compilation and execution; no device-level transport testing is claimed.
