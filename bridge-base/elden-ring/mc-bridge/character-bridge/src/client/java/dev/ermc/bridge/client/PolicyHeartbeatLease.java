package dev.ermc.bridge.client;

/** Measures policy silence in running time; a paused local server cannot send its heartbeat. */
public final class PolicyHeartbeatLease {
    private static final long TIMEOUT_NS = 3_000_000_000L;
    private long lastObserved, runningSilence;
    private boolean received, wasPaused;

    public void receive(long now) {
        received = true;
        lastObserved = now;
        runningSilence = 0;
    }

    public boolean expired(long now, boolean localServerPaused) {
        if (!received) return false;
        long elapsed = Math.max(0L, now - lastObserved);
        // Include neither a paused interval nor the gap between its final tick and resume.
        // This is clock accounting only: an explicit revocation never calls this helper.
        if (!localServerPaused && !wasPaused) runningSilence = Math.min(TIMEOUT_NS, runningSilence + elapsed);
        lastObserved = now;
        wasPaused = localServerPaused;
        return !localServerPaused && runningSilence >= TIMEOUT_NS;
    }

    public void reset() {
        received = wasPaused = false;
        lastObserved = runningSilence = 0;
    }
}
