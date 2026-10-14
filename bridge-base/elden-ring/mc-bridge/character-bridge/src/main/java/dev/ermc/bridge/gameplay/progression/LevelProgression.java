package dev.ermc.bridge.gameplay.progression;

import dev.ermc.bridge.api.HostLevelSnapshot;
import dev.ermc.bridge.api.HostSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-thread-only progression. Binding/session assignment must come from trusted server lifecycle code. */
public final class LevelProgression {
    public enum Outcome { INITIALIZED, ADVANCED, UNCHANGED, LOWER_LEVEL, UNBOUND, WRONG_SESSION, STALE, NOT_READY }

    /** Apply progress.earnedPoints() as the provider's cumulative POINT_SOURCE, never as an increment. */
    public record Observation(Outcome outcome, Optional<LevelProgress> progress) {}

    private static final class Connection {
        final HostSession session;
        long lastSequence = -1;
        boolean loaded;
        LevelProgress progress;
        Connection(HostSession session) { this.session = session; }
    }

    private final LevelProgressStore store;
    private final int pointsPerLevel;
    private final int startingPoints;
    private final Map<UUID, Connection> connections = new HashMap<>();

    public LevelProgression(LevelProgressStore store, int pointsPerLevel, int startingPoints) {
        this.store = Objects.requireNonNull(store, "store");
        if (pointsPerLevel < 1 || startingPoints < 0) throw new IllegalArgumentException("Invalid point defaults");
        this.pointsPerLevel = pointsPerLevel;
        this.startingPoints = startingPoints;
    }

    /** Repeated registration of the same session retains its sequence fence. */
    public void connect(HostSession session) {
        Objects.requireNonNull(session, "session");
        var current = connections.get(session.binding().playerId());
        if (current != null) {
            if (current.session.equals(session)) return;
            if (current.session.sessionId().equals(session.sessionId())) {
                throw new IllegalArgumentException("A new binding requires a fresh session ID");
            }
        }
        connections.put(session.binding().playerId(), new Connection(session));
    }

    /** A delayed disconnect from the old connection cannot close its replacement. */
    public boolean disconnect(UUID playerId, UUID expectedSession) {
        var current = connections.get(playerId);
        if (current == null || !current.session.sessionId().equals(expectedSession)) return false;
        connections.remove(playerId);
        return true;
    }

    /** authenticatedPlayer is taken from the connection, not the payload's claimed player ID. */
    public Observation observe(UUID authenticatedPlayer, HostLevelSnapshot snapshot) throws IOException {
        Objects.requireNonNull(authenticatedPlayer, "authenticatedPlayer");
        Objects.requireNonNull(snapshot, "snapshot");
        var current = connections.get(authenticatedPlayer);
        if (current == null) return rejected(Outcome.UNBOUND);
        if (!current.session.equals(snapshot.session())) return rejected(Outcome.WRONG_SESSION);
        if (snapshot.sequence() <= current.lastSequence) return rejected(Outcome.STALE);
        if (snapshot.state() != HostLevelSnapshot.State.READY) {
            current.lastSequence = snapshot.sequence();
            return rejected(Outcome.NOT_READY);
        }

        LevelProgress before = current.loaded ? current.progress : store.load(current.session.binding()).orElse(null);
        if (before != null && !before.binding().equals(current.session.binding())) {
            throw new IOException("Progress store returned a different character binding");
        }
        LevelProgress after = before == null
                ? new LevelProgress(current.session.binding(), snapshot.level(), snapshot.level(), pointsPerLevel, startingPoints)
                : before.observe(snapshot.level());
        Outcome outcome = before == null ? Outcome.INITIALIZED
                : snapshot.level() > before.highestLevel() ? Outcome.ADVANCED
                : snapshot.level() < before.highestLevel() ? Outcome.LOWER_LEVEL : Outcome.UNCHANGED;
        if (!after.equals(before)) store.save(after);
        // Only acknowledge after persistence succeeds. An I/O error leaves this snapshot retryable.
        current.progress = after;
        current.loaded = true;
        current.lastSequence = snapshot.sequence();
        return new Observation(outcome, Optional.of(after));
    }

    private static Observation rejected(Outcome outcome) {
        return new Observation(outcome, Optional.empty());
    }
}
