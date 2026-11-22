# Experimental PCM Recovery Decision Engine

This milestone adds a conservative, read-only policy layer over integrity assessments.

## Decision classes
- **RECOVERABLE**: the assessment is clean, journal and checkpoint agree, trusted bytes are available, and file size equals the checkpoint. Playback still needs verification.
- **PARTIALLY_SALVAGEABLE**: integrity metadata identifies a trusted checkpoint prefix, but the assessment is not clean. Only that prefix may be considered; never overwrite the original automatically.
- **REJECTED**: orphan files, finalized/failed sessions, missing integrity assessment, invalid metadata, checkpoint disagreement, or no usable trusted bytes.

## Output
The decision contains trusted byte count, PCM duration estimate (PCM16 mono at the recorded sample rate), explanatory reasons, and a confirmation requirement.

## Safety boundaries
- Classification only; no resume, truncation, deletion, conversion, or file mutation.
- Always requires explicit user confirmation before any future recovery operation.
- Duration is an estimate based on byte count and sample rate.
- Hash/checkpoint consistency does not establish audible quality or successful playback.
- This remains experimental and is not connected to the production recording service.
