# Experimental PCM Recovery Artifact Lifecycle

## Scope
The lifecycle manager inventories artifacts inside the recovery directory and provides an explicit cleanup plan. It does not touch original session files or automatically delete anything.

## Recognized types
- recovered PCM and its recovery report
- WAV output
- pipeline report
- temporary files

Unknown file names are excluded from cleanup eligibility.

## Safety model
1. Inventory is read-only and restricted to files directly inside the canonical recovery directory.
2. Cleanup is limited to explicit names selected from an inventory.
3. Cleanup requires explicit confirmation.
4. Cleanup rechecks canonical containment, recognized type, and size before deletion.
5. Any mismatch aborts cleanup for that artifact. This is not an atomic multi-file cleanup; earlier files may already have been deleted if a later file fails.
6. No original recording/session files are deleted.

## Limitations
This is an experimental file lifecycle utility, not a complete UI. It does not provide retention scheduling, trash/undo, or durable cleanup logs. Tests have been authored but not run.
