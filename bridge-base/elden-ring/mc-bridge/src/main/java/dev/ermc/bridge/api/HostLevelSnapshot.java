package dev.ermc.bridge.api;

import java.util.Objects;

/** Semantic snapshot, separate from the shared-memory ABI and any future network encoding. */
public record HostLevelSnapshot(HostSession session, long sequence, State state, int level) {
    public enum State { READY, LOADING, UNAVAILABLE }

    public HostLevelSnapshot {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(state, "state");
        if (sequence < 0) throw new IllegalArgumentException("Negative sequence");
        if (state == State.READY ? level < 1 : level != 0) {
            throw new IllegalArgumentException("Ready snapshots require a positive level; other states use zero");
        }
    }
}
