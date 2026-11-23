# Experimental PCM Recovery Pipeline

The orchestrator composes the recovery decision engine, trusted-prefix executor, WAV converter and Android MediaExtractor readiness probe.

- Rejects orphan/invalid assessments before filesystem writes.
- Requires explicit user confirmation.
- Creates a separate trusted PCM recovery file, then a separate WAV file.
- Probes WAV using MediaExtractor and reports VALID_READABLE or validation failure.
- Emits a pipeline report when conversion and probe complete.
- Source files remain untouched; no automatic recovery or playback.
- Status COMPLETED_VALID_READABLE is media-framework readiness only, not audible playback verification.

If a downstream step fails, prior recovery artifacts may remain for inspection; reruns may require manual review because existing outputs are intentionally not overwritten. Experimental only; unit tests are not executed by this commit.


## Reliability hardening

- Pipeline now checks deterministic WAV/report output collisions before copying the trusted PCM artifact.
- Pipeline report is written to a temporary report path and renamed to its final path after successful write.
- Added a regression test ensuring report collision leaves pre-existing report unchanged and avoids PCM copy.
- Atomic rename behavior remains filesystem-dependent; downstream failure can leave earlier recovery artifacts for manual inspection.
