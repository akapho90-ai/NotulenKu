# CI failure remediation: deprecated temp-directory helper

A CI run reached Kotlin unit-test compilation and failed because Kotlin now treats the legacy `createTempDir()` helper's security deprecation as an error. The affected audio recovery tests were updated to use `kotlin.io.path.createTempDirectory().toFile()`. This is test-only and does not alter production behavior.

Validation remains pending on a fresh workflow run.
