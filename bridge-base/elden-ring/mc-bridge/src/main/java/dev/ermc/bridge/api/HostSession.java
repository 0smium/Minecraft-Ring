package dev.ermc.bridge.api;

import java.util.Objects;
import java.util.UUID;

/** Ephemeral connection identity; reconnect with a fresh ID while retaining the character binding. */
public record HostSession(CharacterBinding binding, UUID sessionId) {
    public HostSession {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
