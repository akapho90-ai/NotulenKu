# Audit: coroutine cancellation during provider disconnect

## Finding
`LiveTranscriptionConnectionManager.disconnectQuietly()` previously caught every `Throwable`, including coroutine cancellation. This could suppress cancellation while the worker was stopping, delaying or interfering with lifecycle shutdown.

## Remediation
- Provider-close timeout is treated as best-effort and suppressed.
- External coroutine cancellation is rethrown.
- Other provider close failures remain suppressed so they do not mask the primary transport/lifecycle error.

## Scope
This is a targeted lifecycle correctness fix. It does not change frame retry semantics (ambiguous delivery is not retried), or introduce concurrent connection operations.

## Verification
Source review and CI regression workflow pending. Device/provider behavior is not verified by this source change alone.
