# Experimental PCM Recovery WAV Conversion

- Requires explicit user confirmation.
- Converts raw PCM16 mono into a standard WAV with RIFF, fmt and data headers.
- Supports sample rates from 8 kHz through 192 kHz and rejects empty, odd-byte or oversized RIFF payloads.
- Writes to a temporary file, synchronizes, validates header/format/length and SHA-256, then renames to a new WAV file.
- Refuses collisions and leaves the source PCM unchanged.
- Estimates duration from sample count and sample rate.

Limitations: this validates structural WAV integrity, not subjective audio quality, playback on devices, or integration with production recording. Experimental utility only; tests have not been run.
