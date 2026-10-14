package dev.ermc.bridge.client;

/** Pure, bounded handoff of one MC-originated action; it never infers menus from input locks. */
public final class InteractionHandoffPolicy {
    public enum Decision { NONE, TO_HOST, TO_MC }
    public record Identity(int pid, long started, int generation, int life, int zone) {}
    private Identity identity;
    private int request;
    private boolean acknowledged, handedOff;
    private long requestedAt, resumeSince = -1, resumeFrame;
    private static final long OPEN_WINDOW_MS = 8000, RETURN_STABLE_MS = 350;

    public void request(int sequence, Identity observed, long now) {
        cancel();
        if (sequence != 0 && observed != null) { request = sequence; identity = observed; requestedAt = now; }
    }
    public void acknowledge(int sequence, int result) {
        if (request == 0 || sequence != request || handedOff) return;
        if (result > 0) acknowledged = true;
        else cancel();
    }
    public boolean pending() { return request != 0; }
    public void cancel() {
        identity = null; request = 0; acknowledged = handedOff = false;
        resumeSince = -1; requestedAt = resumeFrame = 0;
    }
    public Decision observe(Identity current, boolean valid, boolean menuBlocking, boolean canResume,
                            boolean hostMode, boolean otherApp, boolean gameFocused, long frame, long now) {
        if (!pending()) return Decision.NONE;
        if (!valid || !identity.equals(current) || otherApp || now < requestedAt) { cancel(); return Decision.NONE; }
        if (!handedOff) {
            if (hostMode || now - requestedAt > OPEN_WINDOW_MS) { cancel(); return Decision.NONE; }
            if (acknowledged && menuBlocking && gameFocused) {
                handedOff = true; resumeSince = -1; return Decision.TO_HOST;
            }
        } else {
            if (!hostMode) { cancel(); return Decision.NONE; }
            if (menuBlocking || !canResume || !gameFocused) { resumeSince = -1; return Decision.NONE; }
            if (resumeSince < 0) { resumeSince = now; resumeFrame = frame; }
            if (now - resumeSince >= RETURN_STABLE_MS && frame > resumeFrame) {
                cancel(); return Decision.TO_MC;
            }
        }
        return Decision.NONE;
    }
}
