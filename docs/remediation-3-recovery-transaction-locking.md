# Recovery Transaction & Locking Remediation

## Changes
- Added keyed JVM-wide session locking based on canonical root path and session identifier. This serializes concurrent recovery attempts across executor/pipeline instances in the same process.
- The pipeline now holds the session lock for preflight, PCM copy, WAV conversion, validation and pipeline-report promotion. The executor lock is reentrant under the same key.
- Recovery executor now refuses pre-existing final or temporary artifacts, syncs PCM and report temp files before promotion, and cleans its own promoted PCM if report promotion fails.
- Source PCM remains read-only and original artifacts are preserved.

## Static review findings addressed
- Concurrent duplicate runs could race through collision preflight and create artifacts, especially with separate pipeline/executor instances.
- Report and PCM temp artifacts were not explicitly fsynced before promotion.
- Existing temp collisions were not preflighted in the executor.

## Limitations
- Locking is process/JVM-local; it does not coordinate across multiple processes or devices.
- Filesystem rename semantics and directory durability vary; no directory fsync or fully atomic multi-file transaction is provided.
- Failures after WAV conversion can leave PCM/WAV artifacts for manual inspection; cleanup is intentionally not automatic.
- No Android build, runtime, concurrency stress, crash-injection or playback test was run in this source-only remediation.
