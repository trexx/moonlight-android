package com.limelight.binding.video;

/**
 * Notified when the decoder fails unrecoverably.
 *
 * <p>Implemented by {@code Game}, which persists a crash count so the next session can start with
 * riskier decoder features disabled — see {@code MediaCodecDecoderRenderer}'s
 * {@code consecutiveCrashCount}. Called from whichever decoder thread hit the failure.
 */
public interface CrashListener {
    /** @param e the failure, already annotated with stream state for reporting */
    void notifyCrash(Exception e);
}
