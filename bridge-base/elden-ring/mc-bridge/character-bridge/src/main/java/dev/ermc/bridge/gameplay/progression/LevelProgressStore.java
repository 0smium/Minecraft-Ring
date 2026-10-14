package dev.ermc.bridge.gameplay.progression;

import dev.ermc.bridge.api.CharacterBinding;
import java.io.IOException;
import java.util.Optional;

/** One logical server owns a store. Errors must not silently reset a character's baseline. */
public interface LevelProgressStore {
    Optional<LevelProgress> load(CharacterBinding binding) throws IOException;
    void save(LevelProgress progress) throws IOException;
}
