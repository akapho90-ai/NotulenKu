# Capture-to-transcription adapter

`LiveCaptureTranscriptionAdapter` is a small, provider-independent bridge between raw PCM reads and the bounded transcription frame queue.

## Responsibilities
- Pass PCM reads into `PcmFrameAssembler`.
- Enqueue assembled frames into `LiveTranscriptionBuffer`.
- Expose FIFO polling to a separate provider worker.
- Count frames rejected by the bounded queue.
- Retain incomplete frame bytes across capture reads.

## Overflow behavior
The queue is bounded. If capacity is reached, the adapter increments `droppedFrameCount` and continues processing without throwing or affecting the capture engine. The consumer should treat nonzero drops as a signal that live transcription may be incomplete; this does not imply the local recording is incomplete.

## Threading
`acceptPcm` and `clear` are synchronized. Queue polling is backed by the thread-safe queue. Consumer/provider work should remain separate from the capture callback.

## Tests
Unit tests cover queue handoff, FIFO sequence, overflow accounting, and carry bytes across partial reads.

## Not yet wired
This adapter is not connected to the production recorder or provider/network code. Build and device tests remain outstanding.
