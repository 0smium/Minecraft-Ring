package dev.ermc.bridge.api;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable per-character policy view. Adapters must authorize against the current server view. */
public record CapabilitySnapshot(CharacterBinding binding, long policyRevision, Map<Ability, Grant> abilities) {
    public enum Ability { SPEED, AURA }

    public record NumericRange(double minimum, double maximum) {
        public NumericRange {
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum) {
                throw new IllegalArgumentException("Invalid numeric range");
            }
        }

        public double clamp(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite capability value");
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    /** Modes are bridge identifiers; a client adapter maps them to its supported implementation. */
    public record Grant(int rank, boolean visible, Set<String> allowedModes, Map<String, NumericRange> numericLimits) {
        public Grant {
            allowedModes = Set.copyOf(allowedModes);
            numericLimits = Map.copyOf(numericLimits);
            if (rank < 0 || allowedModes.stream().anyMatch(String::isBlank)
                    || numericLimits.keySet().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Invalid grant");
            }
            if (rank == 0 ? !allowedModes.isEmpty() || !numericLimits.isEmpty() : allowedModes.isEmpty()) {
                throw new IllegalArgumentException("Locked grants have no settings; unlocked grants require modes");
            }
        }

        public boolean unlocked() { return rank > 0; }

        /** Used after config merging, before activation/side effects. Unknown or missing fields fail closed. */
        public Map<String, Double> validateAndClamp(String mode, Map<String, Double> requested) {
            if (!unlocked()) throw new IllegalStateException("Ability is locked");
            if (!allowedModes.contains(mode)) throw new IllegalArgumentException("Unsupported capability mode");
            if (!numericLimits.keySet().equals(requested.keySet())) {
                throw new IllegalArgumentException("Capability settings do not match the current schema");
            }
            Map<String, Double> normalized = new HashMap<>();
            numericLimits.forEach((name, limit) -> normalized.put(name, limit.clamp(requested.get(name))));
            return Map.copyOf(normalized);
        }
    }

    public CapabilitySnapshot {
        Objects.requireNonNull(binding, "binding");
        if (policyRevision < 1) throw new IllegalArgumentException("Policy revision must be positive");
        abilities = Map.copyOf(abilities);
        if (!abilities.keySet().equals(EnumSet.allOf(Ability.class))) {
            throw new IllegalArgumentException("Every known ability needs an explicit grant");
        }
    }

    /** Call on the current snapshot, never on a snapshot supplied by a client. */
    public Map<String, Double> authorize(Ability ability, long expectedRevision, String mode,
                                         Map<String, Double> requested) {
        Objects.requireNonNull(ability, "ability");
        if (expectedRevision != policyRevision) throw new IllegalStateException("Stale capability policy");
        return abilities.get(ability).validateAndClamp(mode, requested);
    }
}
