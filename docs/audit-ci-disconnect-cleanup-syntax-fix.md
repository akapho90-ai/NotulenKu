# CI Audit: Disconnect Cleanup Syntax Fix

The CI run for the provider-disconnect cancellation change failed at Kotlin compilation. The `disconnectQuietly()` block contained literal escaped newline sequences rather than actual line breaks, causing syntax errors around the close call and catch clauses.

Corrected the source formatting while retaining the cancellation policy: suppress timeout and ordinary close failures, but rethrow coroutine cancellation. This is a source-level correction; CI must verify compilation and tests.
