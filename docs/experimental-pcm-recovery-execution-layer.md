# Experimental PCM Recovery Execution Layer

- Explicit user confirmation required; rejected decisions cannot execute.
- Copies exactly the trusted byte prefix to a separate recovered directory.
- Validates byte count and PCM16 alignment, and compares SHA-256 when assessment hash is present.
- Refuses existing output rather than overwriting.
- Writes a plain-text report with source, classification, byte count, sample rate, duration estimate, SHA-256 and playback/source-modification flags.
- Source file is not modified.

Limitations: no format conversion, playback validation, automatic resume, or production recording integration. Experimental only.
