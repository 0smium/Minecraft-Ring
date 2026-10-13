package dev.ermc.bridge.link;

/** Deduplicates optional frost bursts; reconnect/F8/loading/first read never replay historical damage. */
public final class HostEffectCursor {
    private boolean known;
    private long frame;
    private int life, frostProcs;

    public boolean observe(HostEffectState state) {
        if (state.version < 2 || !state.has(Protocol.HOST_EFFECT_VALID)
                || !state.has(Protocol.HOST_EFFECT_MC_CONTROL) || state.frame <= 0) {
            reset();
            return false;
        }
        boolean continuous = known && life == state.hostLife && state.frame > frame;
        if (known && life == state.hostLife && state.frame <= frame) return false;
        long delta = Integer.toUnsignedLong(state.frostProcs - frostProcs);
        boolean burst = continuous && delta == 1 && state.has(Protocol.HOST_EFFECT_FROST);
        known = true;
        frame = state.frame;
        life = state.hostLife;
        frostProcs = state.frostProcs;
        return burst;
    }

    public void reset() { known = false; }
}
