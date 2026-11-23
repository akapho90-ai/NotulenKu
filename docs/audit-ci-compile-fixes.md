# Audit continuation: compiler findings

The first CI regression run surfaced Kotlin compilation errors before tests ran:
- ExperimentalLiveCaptureSession did not retain the sessionId constructor parameter for references in the capture error/start callbacks.
- LiveCaptureTranscriptionAdapter used @Synchronized on a property getter declaration in an invalid target position.

Both issues were fixed on the feature branch. The session ID is now a private constructor property and droppedFrameCount uses synchronized(this) in its getter.

This CI failure is a compile failure, not a test failure.