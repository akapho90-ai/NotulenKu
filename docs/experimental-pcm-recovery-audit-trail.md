# Experimental PCM Recovery Audit Trail

The recovery pipeline records lifecycle events to `recovered/recovery-audit.log`.

## Events
- STARTED: confirmed pipeline execution begins
- COMPLETED: WAV validation reports readable
- VALIDATION_FAILED: pipeline artifacts were created but validation did not pass
- FAILED: pipeline execution encountered an exception

## Data
Each record includes a UUID event identifier, session ID, event type, timestamp, artifact names, and short detail. Delimiter and newline escaping is used. Events can be read back and filtered by session ID.

## Safety and limits
- Log is scoped to the recovery directory; no original audio is altered.
- Each event is persisted through a temporary file and rename.
- A stale temp log causes write refusal for manual inspection.
- File-based log rewrite/rename behavior depends on the filesystem and is not a transactional database.
- No cryptographic tamper protection, retention policy, or concurrent cross-process lock is provided.
- Audit recording failure on pipeline failure is best-effort; it is not guaranteed to replace the primary failure.
- Tests authored, not executed.
