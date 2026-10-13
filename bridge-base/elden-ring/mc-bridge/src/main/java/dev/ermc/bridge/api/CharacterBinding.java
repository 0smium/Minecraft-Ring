package dev.ermc.bridge.api;

import java.util.Objects;
import java.util.UUID;

/** Persistent server-assigned identity. None of these IDs is a native pointer or process ID. */
public record CharacterBinding(UUID playerId, UUID campaignId, UUID characterId) {
    public CharacterBinding {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(characterId, "characterId");
    }
}
