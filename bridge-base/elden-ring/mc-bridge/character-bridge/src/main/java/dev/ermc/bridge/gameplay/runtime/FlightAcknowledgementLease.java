package dev.ermc.bridge.gameplay.runtime;

/** Client-side request/ack ordering. A stopped or prior-native-life ack cannot revive execution. */
public final class FlightAcknowledgementLease {
    private long sequence, firstActiveSequence, acknowledged, receivedAt, hostStart;
    private int hostLife;
    private boolean requested, accepted;

    public long request(boolean active, long nextHostStart, int nextHostLife) {
        sequence++;
        if (active && (!requested || hostStart != nextHostStart || hostLife != nextHostLife)) {
            firstActiveSequence = sequence; accepted = false;
        }
        hostStart = nextHostStart; hostLife = nextHostLife; requested = active;
        if (!active) accepted = false;
        return sequence;
    }
    public boolean receive(long replySequence, long replyHostStart, int replyHostLife, boolean active, long now) {
        if (!requested || replySequence < firstActiveSequence || replySequence < acknowledged || replySequence > sequence
                || replyHostStart != hostStart || replyHostLife != hostLife
                || active && replySequence == acknowledged) return false;
        acknowledged = replySequence; receivedAt = now; accepted = active;
        return true;
    }
    public boolean requested() { return requested; }
    public boolean active(long now, long currentHostStart, int currentHostLife) {
        long age = now - receivedAt;
        return accepted && requested && age >= 0 && age < FlightActivityLease.DURATION_NANOS
            && hostStart == currentHostStart && hostLife == currentHostLife;
    }
    public void revoke() { requested = accepted = false; receivedAt = firstActiveSequence = 0; }
    public void reset() { revoke(); sequence = acknowledged = hostStart = 0; hostLife = 0; }
}
