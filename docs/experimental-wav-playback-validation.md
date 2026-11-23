# Experimental WAV Playback Readiness Validation

- Uses Android MediaExtractor to probe the container and locate an audio track.
- Reports MIME, sample rate, channel count, duration, file size, and whether the first sample can be read.
- Read-only: no file mutation or playback.
- States are VALID_READABLE, INVALID, or UNREADABLE.

A VALID_READABLE result means the media framework can inspect the file and read a sample; it does not guarantee audible playback, codec/device compatibility in all environments, or sound quality. This is an experimental recovery validation layer, not a production playback feature. Robolectric test added but not executed on this change.
