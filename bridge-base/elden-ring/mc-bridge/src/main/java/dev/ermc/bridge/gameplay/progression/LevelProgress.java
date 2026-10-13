package dev.ermc.bridge.gameplay.progression;

import dev.ermc.bridge.api.CharacterBinding;
import java.util.Objects;

/** Cumulative entitlement only. Skill purchases and refunds belong to the skill-tree provider. */
public record LevelProgress(CharacterBinding binding, int baselineLevel, int highestLevel,
                            int pointsPerLevel, int startingPoints) {
    public static final String POINT_SOURCE = "erbridge:er_level";

    public LevelProgress {
        Objects.requireNonNull(binding, "binding");
        if (baselineLevel < 1 || highestLevel < baselineLevel || pointsPerLevel < 1 || startingPoints < 0) {
            throw new IllegalArgumentException("Invalid level progress");
        }
        // Validate before a value can be persisted or used to grant points.
        Math.addExact(startingPoints, Math.multiplyExact(highestLevel - baselineLevel, pointsPerLevel));
    }

    public int earnedPoints() {
        return Math.addExact(startingPoints, Math.multiplyExact(highestLevel - baselineLevel, pointsPerLevel));
    }

    public LevelProgress observe(int level) {
        if (level < 1) throw new IllegalArgumentException("Invalid level");
        return level <= highestLevel ? this
                : new LevelProgress(binding, baselineLevel, level, pointsPerLevel, startingPoints);
    }
}
