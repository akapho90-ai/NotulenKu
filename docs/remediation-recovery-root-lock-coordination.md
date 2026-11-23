# Recovery Root Lock Coordination

## Change
Cleanup, audit-log mutation, and recovery pipeline now use a shared JVM-local recovery-root lock. Session locks are nested after the root lock using a consistent lock order. This intentionally serializes recovery-directory mutations across sessions within the same session root, trading throughput for safer artifact coordination.

## Regression
Added `ExperimentalRecoverySessionLockTest` verifying a session operation blocks while a root-level mutation lock is held.

## Limitations
- Locks are JVM-local; they do not coordinate separate processes or separate Android app processes.
- Lock map entries are retained for process lifetime.
- This is not an atomic filesystem transaction and does not replace validation, synced writes, or backup/recovery logic.
- Tests and build validation remain subject to CI results.
