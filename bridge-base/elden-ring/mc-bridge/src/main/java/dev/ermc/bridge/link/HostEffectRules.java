package dev.ermc.bridge.link;

import java.util.EnumMap;
import java.util.Map;

/** Minecraft gameplay translations, not reproductions of Elden Ring damage/stamina formulas. */
public final class HostEffectRules {
    private HostEffectRules() {}
    public enum Effect { POISON, WITHER, SLOWNESS, BLINDNESS, NAUSEA, WEAKNESS }
    public record LeaseSpec(Effect effect, int amplifier, int duration, int renewBelow, int extension) {}

    public static Map<Effect, LeaseSpec> desired(HostEffectState state) {
        EnumMap<Effect, LeaseSpec> result = new EnumMap<>(Effect.class);
        if (!state.has(Protocol.HOST_EFFECT_VALID) || !state.has(Protocol.HOST_EFFECT_MC_CONTROL)) return Map.of();
        if (state.has(Protocol.HOST_EFFECT_POISON)) {
            result.put(Effect.POISON, new LeaseSpec(Effect.POISON, 0, 100, 25, 75));
        }
        if (state.has(Protocol.HOST_EFFECT_ROT)) {
            // Wither I has a 40-tick cadence; renewal preserves duration modulo 40.
            result.put(Effect.WITHER, new LeaseSpec(Effect.WITHER, 0, 160, 40, 120));
        }
        if (state.has(Protocol.HOST_EFFECT_FROST)) put(result, Effect.SLOWNESS, 0);
        if (state.has(Protocol.HOST_EFFECT_SLEEP)) {
            put(result, Effect.SLOWNESS, 3); // Stronger than frost; removing sleep restores frost's tier.
            put(result, Effect.BLINDNESS, 0);
        }
        if (state.has(Protocol.HOST_EFFECT_MADNESS)) {
            put(result, Effect.NAUSEA, 0);
            put(result, Effect.WEAKNESS, 0);
        }
        return Map.copyOf(result);
    }

    private static void put(Map<Effect, LeaseSpec> result, Effect effect, int amplifier) {
        result.put(effect, new LeaseSpec(effect, amplifier, 100, 25, 75));
    }
}
