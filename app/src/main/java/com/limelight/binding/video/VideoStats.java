package com.limelight.binding.video;

/**
 * Counters for one measurement window, as displayed by the performance overlay and the
 * end-of-stream summary.
 *
 * <p>Totals only — rates are derived on demand by {@link #getFps(long)} — which is what allows
 * windows to be summed together via {@link #add(VideoStats)}. The decoder keeps three of these: the
 * window being filled, the last completed one, and a running total for the session.
 *
 * <p>Holds no clock of its own: {@link #getFps(long)} is handed the current time by the caller,
 * which already has it. That keeps this class free of Android types so it can be tested on a JVM,
 * and follows the rule for anything the frame path touches — pass the timestamp in rather than
 * routing a clock through an injected indirection.
 *
 * <p>Not synchronised. The instances are written by the decoder threads and read when a window
 * rolls over; the values are statistics, so a torn read costs nothing worse than a slightly odd
 * number on screen.
 */
class VideoStats {

    // Time inside the decoder, and time from frame arrival to presentation
    long decoderTimeMs;
    long totalTimeMs;
    // Frames the host says it sent, including ones lost in transit
    int totalFrames;
    // Frames that actually arrived, and of those, the ones presented rather than dropped
    int totalFramesReceived;
    int totalFramesRendered;
    // Loss episodes, and total frames lost across them. Both matter: one long outage and many
    // short ones lose the same frames but look very different to the user.
    int frameLossEvents;
    int framesLost;
    // Host-reported encode latency in tenths of a millisecond, only counted for frames that
    // carried it — hence the separate frame counter
    char minHostProcessingLatency;
    char maxHostProcessingLatency;
    int totalHostProcessingLatency;
    int framesWithHostProcessingLatency;
    long measurementStartTimestamp;

    /**
     * Merges another window into this one. Minimums treat 0 as "unset" rather than as a real
     * value, since a window with no host latency data would otherwise pin the minimum at zero.
     */
    void add(VideoStats other) {
        this.decoderTimeMs += other.decoderTimeMs;
        this.totalTimeMs += other.totalTimeMs;
        this.totalFrames += other.totalFrames;
        this.totalFramesReceived += other.totalFramesReceived;
        this.totalFramesRendered += other.totalFramesRendered;
        this.frameLossEvents += other.frameLossEvents;
        this.framesLost += other.framesLost;

        // Both sides need the zero check, not just this one. Guarding only `this` still lets
        // Math.min(x, 0) collapse a real minimum to zero when the incoming window carried no host
        // latency at all, which is what happens to the active window during a stall.
        if (this.minHostProcessingLatency == 0) {
            this.minHostProcessingLatency = other.minHostProcessingLatency;
        } else if (other.minHostProcessingLatency != 0) {
            this.minHostProcessingLatency = (char) Math.min(this.minHostProcessingLatency, other.minHostProcessingLatency);
        }
        this.maxHostProcessingLatency = (char) Math.max(this.maxHostProcessingLatency, other.maxHostProcessingLatency);
        this.totalHostProcessingLatency += other.totalHostProcessingLatency;
        this.framesWithHostProcessingLatency += other.framesWithHostProcessingLatency;

        if (this.measurementStartTimestamp == 0) {
            this.measurementStartTimestamp = other.measurementStartTimestamp;
        }

        assert other.measurementStartTimestamp >= this.measurementStartTimestamp;
    }

    /** Overwrites this window with another's values, including its start timestamp. */
    void copy(VideoStats other) {
        this.decoderTimeMs = other.decoderTimeMs;
        this.totalTimeMs = other.totalTimeMs;
        this.totalFrames = other.totalFrames;
        this.totalFramesReceived = other.totalFramesReceived;
        this.totalFramesRendered = other.totalFramesRendered;
        this.frameLossEvents = other.frameLossEvents;
        this.framesLost = other.framesLost;
        this.minHostProcessingLatency = other.minHostProcessingLatency;
        this.maxHostProcessingLatency = other.maxHostProcessingLatency;
        this.totalHostProcessingLatency = other.totalHostProcessingLatency;
        this.framesWithHostProcessingLatency = other.framesWithHostProcessingLatency;
        this.measurementStartTimestamp = other.measurementStartTimestamp;
    }

    /** Resets every counter, ready for a new window. */
    void clear() {
        this.decoderTimeMs = 0;
        this.totalTimeMs = 0;
        this.totalFrames = 0;
        this.totalFramesReceived = 0;
        this.totalFramesRendered = 0;
        this.frameLossEvents = 0;
        this.framesLost = 0;
        this.minHostProcessingLatency = 0;
        this.maxHostProcessingLatency = 0;
        this.totalHostProcessingLatency = 0;
        this.framesWithHostProcessingLatency = 0;
        this.measurementStartTimestamp = 0;
    }

    /**
     * @param nowUptimeMillis the current {@code SystemClock.uptimeMillis()}, on the same clock as
     *                        {@link #measurementStartTimestamp}. Passed in rather than read here so
     *                        this class stays free of Android types.
     * @return frame rates derived from the counters and the time since the window opened. All
     *         zero until the window has been open for a measurable interval.
     */
    VideoStatsFps getFps(long nowUptimeMillis) {
        float elapsed = (nowUptimeMillis - this.measurementStartTimestamp) / (float) 1000;

        VideoStatsFps fps = new VideoStatsFps();
        if (elapsed > 0) {
            fps.totalFps = this.totalFrames / elapsed;
            fps.receivedFps = this.totalFramesReceived / elapsed;
            fps.renderedFps = this.totalFramesRendered / elapsed;
        }
        return fps;
    }
}

/**
 * Frame rates derived from a {@link VideoStats} window.
 *
 * <p>Three of them, because the gaps between them are diagnostic: total above received means
 * network loss, received above rendered means the device can't keep up.
 */
class VideoStatsFps {

    float totalFps;
    float receivedFps;
    float renderedFps;
}