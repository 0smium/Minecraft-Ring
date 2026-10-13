package dev.ermc.bridge.client.integrations;

import dev.ermc.bridge.api.CapabilitySnapshot;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.Grant;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Two fixed 3530 mappings, outside the reserved Mixin package. No LB compile dependency. */
public final class LiquidBounceCapabilityAccess {
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private static final String SPEED = "net.ccbluex.liquidbounce.features.module.modules.movement.speed.ModuleSpeed";
    private static final String AURA = "net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura";
    private static final String STRAFE = "net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes.SpeedCustom$Strafe";
    private static final String TYPES = "net.ccbluex.liquidbounce.config.types.";
    private static final MethodType OBJECT_GET = MethodType.methodType(Object.class, Object.class);
    private static volatile Map<Object, Rule> rules = Map.of();
    private static volatile Map<Object, Object> choices = Map.of();
    private static Binding speed, aura;
    private static Access access;
    private static Object unit;
    private static boolean failed;
    private static long reconciledGeneration = -1;
    private static final ThreadLocal<Boolean> routingEnabled = ThreadLocal.withInitial(() -> false);

    private LiquidBounceCapabilityAccess() {}

    private record Rule(Ability ability, String parameter, Object fixed, boolean enabled) {}

    /** Register after each module's complete static initializer, before ordinary config loading. */
    public static synchronized void register(String className) {
        if (failed) return;
        try {
            if (access == null) access = new Access();
            Class<?> type = Class.forName(className, false, LiquidBounceCapabilityAccess.class.getClassLoader());
            Object module = type.getField("INSTANCE").get(null);
            Binding binding = new Binding(module, className.equals(SPEED) ? Ability.SPEED : Ability.AURA);
            IdentityHashMap<Object, Rule> next = new IdentityHashMap<>(rules);
            IdentityHashMap<Object, Object> nextChoices = new IdentityHashMap<>(choices);
            binding.add(next, "Enabled", new Rule(binding.ability, null, null, true));
            if (binding.ability == Ability.SPEED) {
                Object mode = binding.required("Mode");
                Object custom = binding.choice(mode, "Custom");
                nextChoices.put(mode, custom);
                binding.add(next, "Locked", fixed(Ability.SPEED, false)); // Own policy, not LB's bypass lock.
                for (String path : List.of("OnlyInCombat/Enabled", "OnlyOnPotionEffect/Enabled", "YawOffset/Enabled",
                        "Mode/Custom/HorizontalModification/Enabled", "Mode/Custom/VerticalModification/Enabled"))
                    binding.add(next, path, fixed(Ability.SPEED, false));
                binding.add(next, "Mode/Custom/TimerSpeed", fixed(Ability.SPEED, 1F));
                binding.add(next, "Mode/Custom/Strafe/Enabled", fixed(Ability.SPEED, true));
                binding.add(next, "Mode/Custom/Strafe/CustomSpeed", fixed(Ability.SPEED, true));
                binding.add(next, "Mode/Custom/Strafe/Strength", fixed(Ability.SPEED, 1F));
                binding.add(next, "Mode/Custom/Strafe/VelocityTimeout", fixed(Ability.SPEED, 0));
                binding.add(next, "Mode/Custom/Strafe/StrafeKnock", fixed(Ability.SPEED, false));
                binding.add(next, "Mode/Custom/Strafe/Speed", new Rule(Ability.SPEED, "speed", null, false));
                speed = binding;
            } else {
                binding.add(next, "Range", new Rule(Ability.AURA, "range", null, false));
                binding.add(next, "WallRange", fixed(Ability.AURA, 0F));
                binding.add(next, "ScanExtraRange", fixed(Ability.AURA, 0F));
                for (String path : List.of("Rotations/ThroughWalls", "AutoBlocking/Enabled", "FailSwing/Enabled",
                        "FightBot/Enabled", "SimulateInventoryClosing")) binding.add(next, path, fixed(Ability.AURA, false));
                binding.add(next, "ClickScheduler/Cooldown/Enabled", fixed(Ability.AURA, true));
                Object fullCooldown = (Object) access.floatRange.invokeExact(1F, 1F);
                binding.add(next, "ClickScheduler/Cooldown/Minimum", fixed(Ability.AURA, fullCooldown));
                binding.add(next, "ClickScheduler/ConsiderMissCooldown", fixed(Ability.AURA, true));
                binding.add(next, "Criticals", fixed(Ability.AURA, enumConstant(binding.required("Criticals"), "IGNORE")));
                binding.add(next, "Raycast", fixed(Ability.AURA, enumConstant(binding.required("Raycast"), "TRACE_ONLYENEMY")));
                aura = binding;
            }
            rules = Collections.unmodifiableMap(next);
            choices = Collections.unmodifiableMap(nextChoices);
            LOG.info("B01 LiquidBounce capability mapping ready: {}", binding.ability);
        } catch (Throwable error) {
            failed = true;
            LOG.error("B01 LiquidBounce capability mapping unavailable; challenge modules stay locked", error);
        }
    }

    private static Rule fixed(Ability ability, Object value) { return new Rule(ability, null, value, false); }

    private static Object enumConstant(Object value, String name) throws Throwable {
        Object raw = access.raw.invoke(value);
        for (Object constant : ((Enum<?>) raw).getDeclaringClass().getEnumConstants())
            if (((Enum<?>) constant).name().equals(name)) return constant;
        throw new IllegalStateException("Missing fixed LB enum constant " + name);
    }

    public static boolean challenge() { return CapabilityPolicyClient.view().challengeActive(); }

    private static Ability ability(Object module) {
        String name = module.getClass().getName();
        return name.equals(SPEED) ? Ability.SPEED : name.equals(AURA) ? Ability.AURA : null;
    }

    private static Grant grant(Ability ability) {
        CapabilitySnapshot snapshot = CapabilityPolicyClient.view().snapshot();
        if (snapshot == null || ability == null || failed) return null;
        Grant grant = snapshot.abilities().get(ability);
        String mode = ability == Ability.SPEED ? "ground_custom" : "single_target";
        String key = ability == Ability.SPEED ? "speed" : "range";
        return grant != null && grant.unlocked() && grant.allowedModes().contains(mode)
                && grant.numericLimits().keySet().equals(java.util.Set.of(key)) ? grant : null;
    }

    public static boolean allowModule(Object module) {
        Ability ability = ability(module);
        return !challenge() || ability == null || grant(ability) != null;
    }

    /** Invoked inside LB's activation listener, before enable or choice lifecycle side effects. */
    public static boolean allowActivation(Object module) {
        if (!challenge() || ability(module) == null) return true;
        Binding binding = ability(module) == Ability.SPEED ? speed : aura;
        if (binding == null || !allowModule(module)) {
            if (binding != null) disable(binding);
            return false;
        }
        apply(binding);
        return !failed;
    }

    /** Normal set/get guard for GUI, imports, scripts and execution reads; only registered Value identities. */
    public static Object normalizeValue(Object value, Object input, boolean write) {
        if (!challenge()) return input;
        Rule rule = rules.get(value);
        if (rule == null) {
            Object choice = choices.get(value);
            return choice != null && input instanceof List<?> ? List.of(choice) : input;
        }
        if (rule.enabled) return Boolean.TRUE.equals(input) && grant(rule.ability) != null;
        if (rule.parameter == null) return rule.fixed;
        Grant grant = grant(rule.ability);
        if (grant == null) return 0F; // No numeric authority; the module execution guard is closed.
        if (!(input instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            if (write) throw new IllegalArgumentException("Non-finite B01 capability setting");
            return (float) grant.numericLimits().get(rule.parameter).minimum();
        }
        return (float) grant.numericLimits().get(rule.parameter).clamp(number.doubleValue());
    }

    public static Object normalizeChoice(Object configurable, Object selected) {
        return challenge() ? choices.getOrDefault(configurable, selected) : selected;
    }

    /** Internal/script Enabled writes must still execute LB's lifecycle, not just flip its backing field. */
    public static boolean routeInternalEnabled(Object value, Object requested) {
        if (!challenge() || routingEnabled.get()) return false;
        Rule rule = rules.get(value);
        if (rule == null || !rule.enabled) return false;
        Binding binding = rule.ability == Ability.SPEED ? speed : aura;
        if (binding == null) return true; // Never permit an unbound raw activation.
        routingEnabled.set(true);
        try {
            boolean bounded = Boolean.TRUE.equals(normalizeValue(value, requested, true));
            access.moduleEnabled.invoke(binding.module, bounded);
        } catch (Throwable error) {
            failed = true;
            LOG.error("B01 failed to route internal {} Enabled write normally", binding.ability, error);
        } finally { routingEnabled.set(false); }
        return true;
    }

    /** LB's original Custom.Strafe sequence is active only for ordinary grounded locomotion. */
    public static boolean skipToggleable(Object configurable) {
        if (!challenge() || !configurable.getClass().getName().equals(STRAFE)) return false;
        Minecraft mc = Minecraft.getInstance();
        return grant(Ability.SPEED) == null || mc.player == null || !mc.player.onGround()
                || mc.player.isInWaterOrBubble() || mc.player.isInLava() || mc.player.isPassenger()
                || mc.player.getAbilities().flying;
    }

    public static Object kotlinUnit() {
        if (unit == null) throw new IllegalStateException("LB policy access not initialized");
        return unit;
    }

    /** Recheck immediately before each Aura click, including a sequence resumed after policy lowering. */
    public static boolean allowAuraAttack(Entity target) {
        if (!challenge()) return true;
        Minecraft mc = Minecraft.getInstance();
        if (aura == null || grant(Ability.AURA) == null || mc.player == null || !target.isAlive()
                || mc.player.getAttackStrengthScale(0F) < 1F) return false;
        try {
            float range = (float) normalizeValue(aura.required("Range"), access.raw.invoke(aura.required("Range")), false);
            Vec3 eye = mc.player.getEyePosition();
            AABB box = target.getBoundingBox();
            Vec3 point = new Vec3(Math.max(box.minX, Math.min(box.maxX, eye.x)),
                    Math.max(box.minY, Math.min(box.maxY, eye.y)), Math.max(box.minZ, Math.min(box.maxZ, eye.z)));
            return eye.distanceToSqr(point) <= (double) range * range;
        } catch (Throwable error) { return false; }
    }

    public static void tick(Minecraft mc) {
        if (!challenge()) return;
        CapabilityPolicyClient.View view = CapabilityPolicyClient.view();
        if (reconciledGeneration != view.generation()) {
            for (Binding binding : new Binding[] {speed, aura}) {
                if (binding == null) continue;
                if (grant(binding.ability) == null) disable(binding);
                else apply(binding);
            }
            reconciledGeneration = view.generation();
        }
        // A direct internal/script write cannot leave a locked module enabled between policy updates.
        for (Binding binding : new Binding[] {speed, aura})
            if (binding != null && grant(binding.ability) == null) disable(binding);
    }

    public static void stopManagedModules() {
        for (Binding binding : new Binding[] {speed, aura}) if (binding != null) disable(binding);
    }

    private static void disable(Binding binding) {
        try {
            if (Boolean.TRUE.equals(access.raw.invoke(binding.required("Enabled"))))
                access.moduleEnabled.invoke(binding.module, false); // Executes LB's normal disable/children cleanup.
        } catch (Throwable error) {
            failed = true;
            LOG.error("B01 failed to disable {} normally", binding.ability, error);
        }
    }

    private static void apply(Binding binding) {
        try {
            if (binding.ability == Ability.SPEED) access.choiceSet.invoke(binding.required("Mode"), "Custom");
            for (Map.Entry<String, Object> item : binding.values.entrySet()) {
                Rule rule = rules.get(item.getValue());
                if (rule == null || rule.enabled) continue;
                Object old = access.raw.invoke(item.getValue());
                Object bounded = normalizeValue(item.getValue(), old, false);
                if (!Objects.equals(old, bounded)) access.valueSet.invoke(item.getValue(), bounded);
            }
        } catch (Throwable error) {
            failed = true;
            LOG.error("B01 failed to apply {} capability settings", binding.ability, error);
            disable(binding);
        }
    }

    private static final class Binding {
        final Object module;
        final Ability ability;
        final Map<String, Object> values = new HashMap<>();
        Binding(Object module, Ability ability) throws Throwable { this.module = module; this.ability = ability; walk(module, ""); }
        Object required(String path) {
            Object value = values.get(path);
            if (value == null) throw new IllegalStateException("Fixed 3530 setting missing: " + ability + "/" + path);
            return value;
        }
        void add(Map<Object, Rule> target, String path, Rule rule) { target.put(required(path), rule); }
        Object choice(Object value, String name) throws Throwable {
            for (Object candidate : (List<?>) access.choiceList.invoke(value))
                if (name.equals(access.name.invoke(candidate))) return candidate;
            throw new IllegalStateException("Fixed 3530 choice missing: " + name);
        }
        void walk(Object configurable, String prefix) throws Throwable {
            Object children = access.raw.invoke(configurable);
            if (!(children instanceof List<?> list)) return;
            for (Object child : list) {
                if (!access.valueType.isInstance(child)) continue;
                String name = (String) access.name.invoke(child);
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                values.put(path, child);
                if (access.choiceType.isInstance(child)) {
                    for (Object candidate : (List<?>) access.choiceList.invoke(child))
                        walk(candidate, path + "/" + access.name.invoke(candidate));
                } else walk(child, path);
            }
        }
    }

    private static final class Access {
        final Class<?> valueType, choiceType;
        final MethodHandle raw, name, valueSet, choiceList, choiceSet, moduleEnabled, floatRange;
        Access() throws ReflectiveOperationException {
            ClassLoader loader = LiquidBounceCapabilityAccess.class.getClassLoader();
            Class<?> value = Class.forName(TYPES + "Value", false, loader);
            valueType = value;
            choiceType = Class.forName(TYPES + "ChoiceConfigurable", false, loader);
            Class<?> module = Class.forName("net.ccbluex.liquidbounce.features.module.ClientModule", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            raw = lookup.unreflect(value.getMethod("getInner$liquidbounce")).asType(OBJECT_GET);
            name = lookup.unreflect(value.getMethod("getName")).asType(MethodType.methodType(String.class, Object.class));
            valueSet = lookup.unreflect(value.getMethod("set", Object.class)).asType(MethodType.methodType(void.class, Object.class, Object.class));
            choiceList = lookup.unreflect(choiceType.getMethod("getChoices")).asType(OBJECT_GET);
            choiceSet = lookup.unreflect(choiceType.getMethod("setByString", String.class)).asType(MethodType.methodType(void.class, Object.class, String.class));
            moduleEnabled = lookup.unreflect(module.getMethod("setEnabled$liquidbounce", boolean.class)).asType(MethodType.methodType(void.class, Object.class, boolean.class));
            // ClosedFloatRange is package-private. Resolve Kotlin's public facade factory instead.
            Class<?> ranges = Class.forName("kotlin.ranges.RangesKt", false, loader);
            Class<?> range = Class.forName("kotlin.ranges.ClosedFloatingPointRange", false, loader);
            floatRange = lookup.findStatic(ranges, "rangeTo", MethodType.methodType(range, float.class, float.class))
                    .asType(MethodType.methodType(Object.class, float.class, float.class));
            unit = Class.forName("kotlin.Unit", true, loader).getField("INSTANCE").get(null);
        }
    }
}
