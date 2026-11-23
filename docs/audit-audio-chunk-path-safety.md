# Audio chunk path safety audit

## Finding
The chunk preparation and cleanup paths used the supplied session ID directly as a path component. Although IDs are expected to be generated, these methods are defensive boundaries and should reject path traversal input.

## Fix
- Session IDs must match a restricted alphanumeric, underscore, or hyphen pattern, length 1–128.
- Chunk directory is canonicalized and checked to remain directly under the transcription_chunks root.
- Cleanup checks canonical parent containment before recursive deletion.

## Limitations
This guards session-directory traversal. It does not validate the source recording's ownership or make chunk extraction atomic.