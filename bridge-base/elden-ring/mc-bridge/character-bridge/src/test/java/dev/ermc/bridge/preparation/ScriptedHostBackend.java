package dev.ermc.bridge.preparation;

import dev.ermc.bridge.api.HostBackend;
import dev.ermc.bridge.api.HostLevelSnapshot;
import dev.ermc.bridge.api.HostSession;
import java.util.Optional;

/** SIMULATED host fixture. This class is never included in the production mod. */
final class ScriptedHostBackend implements HostBackend {
    private final HostSession session;
    private long sequence;
    private HostLevelSnapshot latest;

    ScriptedHostBackend(HostSession session) { this.session = session; }

    HostLevelSnapshot level(int level) { return publish(HostLevelSnapshot.State.READY, level); }
    HostLevelSnapshot loading() { return publish(HostLevelSnapshot.State.LOADING, 0); }
    HostLevelSnapshot unavailable() { return publish(HostLevelSnapshot.State.UNAVAILABLE, 0); }
    void disconnect() { latest = null; }

    private HostLevelSnapshot publish(HostLevelSnapshot.State state, int level) {
        latest = new HostLevelSnapshot(session, sequence++, state, level);
        return latest;
    }

    @Override
    public Optional<HostLevelSnapshot> levelSnapshot() { return Optional.ofNullable(latest); }
}
