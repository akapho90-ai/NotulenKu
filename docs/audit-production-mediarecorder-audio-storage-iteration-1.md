# Audit Produksi MediaRecorder & Audio Storage — Iterasi 1

**Branch:** `feature/live-transcription-foundation`  
**Status:** Static source review only; no Android runtime/build/device tests executed.  
**Scope:** Production recording configuration, segment lifecycle, merge, transcription chunking, imported audio, and local storage.

## Executive summary
Production recording is already compressed. Both `MeetingRecordingService` and the `MeetingAudioRecorder` controller configure MPEG-4 container, AAC encoder, 16 kHz, 32 kbps, mono. The service segments recordings approximately every 15 minutes. The immediate recommendation is **do not lower bitrate or re-encode files** before runtime quality/compatibility tests.

## Current format standard (observed in source)
- Container: MPEG-4 audio / M4A
- Codec: AAC
- Sample rate: 16,000 Hz
- Bitrate: 32,000 bps
- Channels: mono
- Estimated nominal storage: ~14.4 MB/hour (~28.8 MB/2h), excluding container overhead.

## Findings
### F1 — Recording compression already configured (positive)
- `MeetingRecordingService.startNewRecorderSegment()` and `MeetingAudioRecorder.startRecording()` both set AAC/MPEG-4, 16 kHz, 32 kbps, mono.
- These are duplicated configuration values; future changes should avoid divergence and consider a shared policy/constants layer once the audit and regression tests permit.

### F2 — Production segments and lifecycle
- The service creates parts under `files/recordings/parts_<sessionId>/`, uses numbered M4A parts, and rotates around 15 minutes.
- Pause/interruption finalizes a segment; resume creates a new segment.
- On stop, the service merges captured segments into `files/recordings/rec_<sessionId>.m4a`.
- The service deletes segment files after a successful merge path based on file existence and size (>256 bytes), while merge itself validates readable audio before returning. Review of merge replacement/verification remains a separate safety priority.

### F3 — Merge correctness/integrity concern (high priority)
- `AudioSegmenter.mergeM4aSegments()` filters candidates and skips incompatible/unreadable files.
- During the mux loop, segment errors are logged and skipped; the function can still proceed to return a successful merged file if the output validates. This may silently produce incomplete recordings.
- In the multi-segment output promotion path, existing output is deleted before rename/copy fallback. Copy fallback is not re-validated after final promotion.
- The single-segment promotion path also deletes an existing destination before promotion and lacks a final verification after copy fallback.
- This is not caused by compression; it is a preservation/integrity risk. Remediation should be handled in the audio-merge safety milestone.

### F4 — STT chunking strategy is size-aware (positive, verify in tests)
- Direct upload threshold is 8 MiB, intended to stay below a 10 MiB backend cap.
- Long files are split using MediaExtractor/MediaMuxer, not raw byte slicing.
- Chunk default is 10 minutes with 2-second overlap, and the duration can be reduced based on source size/bitrate.
- Source audio is not altered during chunk preparation; temp chunks are stored in cache.
- Chunk splitting failure returns an error and cleans temporary chunks while preserving the source.

### F5 — Imported audio is copied, not re-compressed
- `AudioFileHelper.copyUriToInternalFile()` copies selected audio into internal `recordings` storage and does not re-encode it.
- This is desirable for preserving original quality/format, but imported large/high-bitrate files can use significantly more storage than native recordings. Compression should not be applied blindly; provider compatibility and source integrity must be considered.

### F6 — Potential duplicate recorder abstraction
- `MeetingAudioRecorder` in `AudioController.kt` appears to retain a direct recording abstraction with the same format configuration.
- The service-based recording pathway is the primary production flow examined here. Confirm usage/references before deleting or consolidating the controller.

## Proposed audio standard (recommendation, not yet changed)
1. Native meetings: AAC/M4A, mono, 16 kHz, nominal 32 kbps as current default.
2. Do not re-encode already-compressed recordings solely to save space.
3. Preserve user-imported source files; chunk only for STT transport.
4. Retain recovery/source segments until merged output has been validated and the merge is known complete.
5. Consider configurable higher bitrate only if test recordings show intelligibility problems in noisy rooms or distant speakers; benchmark 32 vs 48/64 kbps rather than adopting higher rates by default.

## Runtime validation plan (not executed)
- Record test samples: quiet room, noisy room, distant speaker, multiple speakers, headset/Bluetooth scenarios, and interruption/pause/resume.
- Verify M4A codec metadata, duration, channel count, sampling rate, bitrate/size, playback, and transcription accuracy.
- Measure real file size and compare against nominal estimate.
- Test rotation and merge with 1, 2, and many parts, including invalid/truncated part fixtures; verify that merge failure cannot silently discard segments.
- Test STT chunk sizing around the 8 MiB boundary and long recordings.
- Test imported audio formats and oversized files without mutating originals.
- Execute Android build, unit tests, and device tests when environment becomes available.

## Limitations
This report is based on source review only. No Gradle build, Android runtime test, device recording, codec verification, acoustic quality evaluation, crash/fault injection, or backend request test was executed. No source implementation was changed by this audit report.
