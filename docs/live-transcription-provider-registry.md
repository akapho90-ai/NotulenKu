# Live transcription provider registry

The registry is a provider-selection boundary, not a network client or credential store.

- Provider registrations declare an ID, mode, enabled state, and factory.
- Selection can filter by required mode and only instantiates enabled providers.
- Duplicate IDs and blank IDs are rejected.
- A provider factory returning a provider with a mismatched mode is rejected.
- This registry does not validate protocol correctness, credentials, endpoint security, or provider availability.

The existing capture worker consumes individual audio frames. Until a windowed/batch adapter is implemented, choose only a STREAMING provider for that worker. This registry is not yet wired to recording or the UI.
