# Audio extractor resource-release audit

The chunk extraction helper had early failure returns before the existing catch/release cleanup path (for example when no audio track is present or the track MIME is unsupported). Resource cleanup is now in a finally block so MediaExtractor and MediaMuxer release attempts occur for early returns and exceptions alike.

Source review remediation; Android media behavior requires device-level verification.
