package dev.ermc.bridge.gameplay.capability;

import dev.ermc.bridge.api.CapabilitySnapshot;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.Grant;
import dev.ermc.bridge.api.CapabilitySnapshot.NumericRange;
import dev.ermc.bridge.api.CharacterBinding;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure policy evaluation; the skill provider owns learned ranks, costs, purchases and refunds. */
public final class AbilityPolicy {
    public record Tier(Set<String> allowedModes, Map<String, NumericRange> numericLimits) {
        public Tier {
            // Apply the same validation and defensive copies as the public grant.
            Grant checked = new Grant(1, true, allowedModes, numericLimits);
            allowedModes = checked.allowedModes();
            numericLimits = checked.numericLimits();
        }
    }

    private final long revision;
    private final boolean showLocked;
    private final Map<Ability, List<Tier>> tiers;

    public AbilityPolicy(long revision, boolean showLocked, Map<Ability, List<Tier>> tiers) {
        if (revision < 1) throw new IllegalArgumentException("Policy revision must be positive");
        this.revision = revision;
        this.showLocked = showLocked;
        EnumMap<Ability, List<Tier>> copied = new EnumMap<>(Ability.class);
        tiers.forEach((ability, levels) -> {
            List<Tier> copy = List.copyOf(levels);
            if (copy.isEmpty()) throw new IllegalArgumentException("Each ability needs at least one tier");
            copied.put(ability, copy);
        });
        if (!copied.keySet().equals(EnumSet.allOf(Ability.class))) {
            throw new IllegalArgumentException("Every known ability needs a policy");
        }
        this.tiers = Map.copyOf(copied);
    }

    /** Build a fresh view on rank change or policy reload. Missing ranks mean locked, never fully enabled. */
    public CapabilitySnapshot snapshot(CharacterBinding binding, Map<Ability, Integer> learnedRanks) {
        EnumMap<Ability, Grant> grants = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) {
            int rank = learnedRanks.getOrDefault(ability, 0);
            List<Tier> levels = tiers.get(ability);
            if (rank < 0 || rank > levels.size()) {
                throw new IllegalArgumentException("Unsupported rank for " + ability + "; migrate ranks before reloading");
            }
            if (rank == 0) {
                grants.put(ability, new Grant(0, showLocked, Set.of(), Map.of()));
            } else {
                Tier tier = levels.get(rank - 1);
                grants.put(ability, new Grant(rank, true, tier.allowedModes(), tier.numericLimits()));
            }
        }
        return new CapabilitySnapshot(binding, revision, grants);
    }
}
