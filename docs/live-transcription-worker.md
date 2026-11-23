# Live transcription worker

The experimental worker consumes queued frames in a coroutine and forwards them to a provider abstraction. Provider partial/final callbacks update the session transcript repository.

- Capture remains separate; worker errors only change transcript status.
- Queue consumption suspends when empty instead of busy-looping.
- Provider failures are recorded as FALLBACK status/error. This does not implement automatic provider switching.
- This worker is not connected to the production recorder and requires an explicitly verified provider implementation.
- Tests use fake providers; no live network/provider credentials are involved.
