package dev.ermc.bridge.api;

import java.util.Optional;

/** A local/native or test provider. Empty means disconnected, not a level-zero character. */
public interface HostBackend {
    Optional<HostLevelSnapshot> levelSnapshot();
}
