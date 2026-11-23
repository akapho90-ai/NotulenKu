# Experimental PCM Session Journal & Recovery Assessment

This prototype adds a metadata sidecar journal for the isolated PCM writer and a read-only recovery assessment scanner.

- Each experimental PCM session records session ID, PCM file name, format, sample rate, status, checkpoint byte count and update timestamp.
- Writer checkpoints mark the session RECORDING; writer close marks it RECOVERABLE (meaning a checkpointed file is present, not that playback or resume has been proven).
- The recovery manager assesses journaled PCM files and flags orphan PCM files without journals as untrusted.
- The scanner is read-only: it does not resume capture, rewrite/truncate files, convert to M4A, or delete data.
- Recovery assessment validates basic metadata, file existence, checkpoint bounds and PCM16 alignment only. It is not an audio-quality, playback, or crash-durability guarantee.
- This feature remains experimental and is not connected to MeetingRecordingService.

