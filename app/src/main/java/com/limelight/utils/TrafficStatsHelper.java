package com.limelight.utils;

import android.net.TrafficStats;
import android.os.Process;

/**
 * Samples this app's network throughput for the performance overlay.
 *
 * TrafficStats counters are cumulative since boot, so throughput is derived from the
 * delta between successive samples. Counters are per-UID rather than per-socket, so
 * this includes any other traffic the app happens to be generating.
 */
public class TrafficStatsHelper {
    private final int uid = Process.myUid();

    private long lastRxBytes = TrafficStats.UNSUPPORTED;
    private long lastTxBytes = TrafficStats.UNSUPPORTED;
    private long lastSampleTimeMs;

    private float lastRxKBps;
    private float lastTxKBps;

    /** Samples the counters and recomputes throughput. Call once per stats window. */
    public void sample() {
        long rxBytes = TrafficStats.getUidRxBytes(uid);
        long txBytes = TrafficStats.getUidTxBytes(uid);
        long nowMs = android.os.SystemClock.elapsedRealtime();

        // UNSUPPORTED (-1) means the device does not track per-UID traffic
        if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
            lastRxKBps = lastTxKBps = 0;
            return;
        }

        if (lastRxBytes != TrafficStats.UNSUPPORTED && nowMs > lastSampleTimeMs) {
            float elapsedSecs = (nowMs - lastSampleTimeMs) / 1000.f;
            lastRxKBps = (rxBytes - lastRxBytes) / 1024.f / elapsedSecs;
            lastTxKBps = (txBytes - lastTxBytes) / 1024.f / elapsedSecs;
        }

        lastRxBytes = rxBytes;
        lastTxBytes = txBytes;
        lastSampleTimeMs = nowMs;
    }

    /** @return receive throughput in KB/s over the last sampling window */
    public float getRxKBps() {
        return lastRxKBps;
    }

    /** @return transmit throughput in KB/s over the last sampling window */
    public float getTxKBps() {
        return lastTxKBps;
    }
}
