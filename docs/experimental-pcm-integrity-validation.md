# Experimental PCM Integrity Validation

The experimental PCM recovery assessment now performs read-only checks:

- Confirms session metadata format, sample rate, nonnegative and aligned checkpoint byte count, and canonical file path containment.
- Reads the checkpoint sidecar and validates session, format, sample rate, byte count, bounds and PCM16 alignment.
- Compares checkpoint bytes against journal bytes.
- Calculates SHA-256 over the journal-trusted prefix only when these values match.
- Rejects orphan PCM files as recoverable candidates.
- Returns issue strings and integrity fields to aid diagnosis.

SHA-256 is a consistency fingerprint for the checked bytes, not a comparison against an original reference digest. This does not prove audio playback, content authenticity, process-crash durability, or automatic recovery. The verifier is read-only and remains isolated from production recording.
