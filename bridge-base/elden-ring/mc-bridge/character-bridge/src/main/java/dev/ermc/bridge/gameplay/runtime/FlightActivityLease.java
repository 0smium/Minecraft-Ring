package dev.ermc.bridge.gameplay.runtime;

/** Monotonic request watermark plus a short-lived activity declaration, scoped to one connection. */
public final class FlightActivityLease {
    public static final long DURATION_NANOS = 750_000_000L;
    private long sequence, receivedAt;
    private boolean active;

    public boolean receive(long nextSequence, boolean requested, long now) {
        if (nextSequence <= sequence || nextSequence <= 0) return false;
        sequence = nextSequence; receivedAt = now; active = requested;
        return true;
    }
    public boolean active(long now) {
        long elapsed = now - receivedAt;
        return active && elapsed >= 0 && elapsed < DURATION_NANOS;
    }
    public long sequence() { return sequence; }
    /** Revocation preserves the watermark: queued old declarations cannot reactivate the lease. */
    public void revoke() { active = false; }
    public void reset() { sequence = receivedAt = 0; active = false; }
}
