package dev.ermc.bridge.client.integrations;

import dev.ermc.bridge.api.CapabilitySnapshot;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.Grant;
import dev.ermc.bridge.api.CapabilitySnapshot.NumericRange;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog.Entry;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog.Rank;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog.Setting;
import dev.ermc.bridge.gameplay.capability.ModuleCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

/** Fixed LB #3530 adapter. The shared catalog describes policy; LB still executes every module. */
public final class LiquidBounceCapabilityAccess {
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private static final String TYPES = "net.ccbluex.liquidbounce.config.types.";
    private static final String STRAFE = "net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes.SpeedCustom$Strafe";
    private static final MethodType OBJECT_GET = MethodType.methodType(Object.class, Object.class);
    private static volatile Map<Object, Rule> rules = Map.of();
    private static volatile Map<Object, Binding> bindings = Map.of();
    private static Access access;
    private static Object unit;
    private static long reconciledGeneration = -1;
    private static boolean guiRefreshPending;
    private static long guiRefreshAfter;
    private static final ThreadLocal<Boolean> routingEnabled = ThreadLocal.withInitial(() -> false);
    private LiquidBounceCapabilityAccess() {}

    private record Rule(Binding binding, String path, boolean enabled, Object original, Object vanillaRange) {}

    /** ModuleManager.addModule TAIL: full object and config initialization, including script registrations. */
    public static synchronized void register(Object module) {
        if (bindings.containsKey(module)) return;
        try {
            if (access == null) access = new Access();
            String name = (String) access.name.invoke(module);
            boolean builtIn = module.getClass().getName().startsWith("net.ccbluex.liquidbounce.features.module.modules.")
                    && module.getClass().getSimpleName().equalsIgnoreCase("Module" + name);
            Entry entry = builtIn ? AbilityCatalog.instance().entryForModule(name).orElse(null) : null;
            boolean free = builtIn && AbilityCatalog.instance().freeModules().contains(name);
            Binding binding = new Binding(module, name, entry, free);
            IdentityHashMap<Object, Rule> next = new IdentityHashMap<>(rules);
            Object enabled = binding.required("Enabled");
            next.put(enabled, new Rule(binding, "Enabled", true, false, null));
            if (entry != null) {
                for (Entry route : AbilityCatalog.instance().entriesForModule(name))
                for (Rank rank : route.ranks()) for (Setting setting : rank.settings().values()) {
                    Object value = binding.required(setting.path());
                    if (!next.containsKey(value)) next.put(value, new Rule(binding, setting.path(), false,
                            access.raw.invoke(value), access.rangeType.isInstance(value) ? access.rangeField.get(value) : null));
                    binding.validate(setting, next.get(value));
                }
            }
            rules = Collections.unmodifiableMap(next);
            IdentityHashMap<Object, Binding> nextBindings = new IdentityHashMap<>(bindings);
            nextBindings.put(module, binding); bindings = Collections.unmodifiableMap(nextBindings);
            if (entry != null) LOG.info("Growth LB mapping ready: {} / {} ranks", name, entry.ranks().size());
            if (challenge() && !allowModule(module)) disable(binding);
        } catch (Throwable error) {
            // An unregistered module is denied by allowModule in challenge, rather than accidentally free.
            LOG.error("Growth LB mapping failed: {}; challenge execution remains locked", module.getClass().getName(), error);
        }
    }

    public static boolean challenge() { return CapabilityPolicyClient.view().challengeActive(); }
    private static Grant grant(Binding binding) {
        return grant(binding, CapabilityPolicyClient.view().snapshot());
    }
    private static Grant grant(Binding binding, CapabilitySnapshot snapshot) {
        if (binding == null || binding.failed || binding.entry == null || snapshot == null) return null;
        return ModuleCapability.validGrant(binding.entry,snapshot);
    }
    /** REST reads one immutable policy view, without changing module Hidden or the live registry. */
    public static Set<String> guiModules(CapabilityPolicyClient.View view) {
        Set<String> names = new HashSet<>();
        for (Binding binding : bindings.values())
            if (binding.free || !binding.failed && grant(binding, view.snapshot()) != null) names.add(binding.name);
        return Set.copyOf(names);
    }
    public static ModuleCapability.Resolved guiCapability(String moduleName, CapabilityPolicyClient.View view) {
        for (Binding binding:bindings.values()) if(binding.name.equals(moduleName) && grant(binding,view.snapshot())!=null)
            return capability(binding,view);
        return null;
    }
    /** Catalog enum constants and LB NamedChoice labels are different (DOUBLE_JUMP / DoubleJump). */
    public static Setting guiSetting(String moduleName, Setting setting) {
        if (!setting.type().startsWith("choice")) return setting;
        for (Binding binding : bindings.values()) if (binding.name.equals(moduleName)) {
            Object value = binding.values.get(setting.path());
            if (value == null || access.choiceType.isInstance(value)) return setting;
            try {
                String fallback = (String) access.namedChoice.invoke(binding.select(value, setting.stringValue()));
                Set<String> allowed = new HashSet<>();
                for (String choice : setting.allowedChoices()) allowed.add((String) access.namedChoice.invoke(binding.select(value, choice)));
                return new Setting(setting.type(), setting.path(), setting.limitKey(), fallback, setting.booleanValue(), allowed);
            } catch (Throwable error) { LOG.warn("Growth could not project LB choices for {}/{}", moduleName, setting.path(), error); return null; }
        }
        return null;
    }
    private static ModuleCapability.Resolved capability(Binding binding) {
        return capability(binding,CapabilityPolicyClient.view());
    }
    private static ModuleCapability.Resolved capability(Binding binding,CapabilityPolicyClient.View view) {
        synchronized(binding) {
            if(binding.capabilityGeneration!=view.generation()) {
                binding.capability=ModuleCapability.resolve(binding.entry,view.snapshot());
                binding.capabilityGeneration=view.generation();
            }
            return binding.capability;
        }
    }
    private static Setting setting(Rule rule) {
        return capability(rule.binding).settings().get(rule.path);
    }
    public static boolean allowModule(Object module) {
        if (!challenge()) return true;
        Binding binding = bindings.get(module);
        return binding != null && (binding.free || grant(binding) != null);
    }
    public static boolean allowActivation(Object module) {
        if (!challenge()) return true;
        Binding binding = bindings.get(module);
        if (!allowModule(module)) return false;
        if (!binding.free) apply(binding);
        return !binding.failed;
    }
    public static boolean isActive(String moduleName) {
        for (Binding binding : bindings.values()) if (binding.name.equals(moduleName) && allowModule(binding.module)) {
            try { return Boolean.TRUE.equals(access.raw.invoke(binding.required("Enabled"))); }
            catch (Throwable ignored) { return false; }
        }
        return false;
    }

    /** Guards setters, config imports, scripts, direct backing writes and delegated execution reads. */
    public static Object normalizeValue(Object value, Object input, boolean write) {
        if (!challenge()) return input;
        Rule rule = rules.get(value);
        if (rule == null) return input;
        if (rule.enabled) return Boolean.TRUE.equals(input) && allowModule(rule.binding.module);
        Setting setting = setting(rule);
        if (setting == null) return rule.original; // A lower rank cannot inherit an upper-only override.
        try {
            return switch (setting.type()) {
                case "boolean" -> setting.booleanValue();
                case "choice", "choice_set" -> {
                    Object candidate = access.choiceType.isInstance(value) && input instanceof List<?> list ? (list.isEmpty() ? null : list.get(0)) : input;
                    Object selected = effectiveChoice(rule.binding, value, setting, candidate);
                    yield access.choiceType.isInstance(value) ? List.of(selected) : selected;
                }
                case "number" -> number(input, rule.original, bounds(rule, setting), write);
                case "range" -> {
                    NumericRange bounds = bounds(rule, setting);
                    if (!access.closedRangeType.isInstance(input)) {
                        if (write) throw new IllegalArgumentException("Invalid growth setting range");
                        input = rule.original;
                    }
                    Number first = (Number) access.rangeStart.invoke(input);
                    Number last = (Number) access.rangeEnd.invoke(input);
                    Number prototype = (Number) access.rangeStart.invoke(rule.original);
                    Number lo = (Number) number(first, prototype, bounds, write);
                    Number hi = (Number) number(last, prototype, bounds, write);
                    yield access.makeRange(prototype, Math.min(lo.doubleValue(), hi.doubleValue()), Math.max(lo.doubleValue(), hi.doubleValue()));
                }
                default -> throw new IllegalStateException("Unknown growth setting type");
            };
        } catch (RuntimeException error) { throw error; }
        catch (Throwable error) { rule.binding.failed = true; throw new IllegalStateException("Growth mapping failed: " + rule.path, error); }
    }
    private static Object number(Object input, Object prototype, NumericRange range, boolean write) {
        if (!(input instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            if (write) throw new IllegalArgumentException("Non-finite growth capability setting");
            input = range.minimum();
        }
        double value = range.clamp(((Number) input).doubleValue());
        if (prototype instanceof Integer) return (int) Math.max(Math.ceil(range.minimum()), Math.min(Math.floor(range.maximum()), Math.round(value)));
        if (prototype instanceof Long) return Math.round(value);
        if (prototype instanceof Double) return value;
        if (prototype instanceof Float) return (float) value;
        throw new IllegalArgumentException("Unsupported LB numeric value type");
    }
    private static NumericRange bounds(Rule rule, Setting setting) throws Throwable {
        NumericRange policy = capability(rule.binding).limits().get(setting.limitKey());
        if (rule.vanillaRange == null) throw new IllegalStateException("Numeric setting has no vanilla range: " + rule.path);
        Number originalStart = (Number) access.rangeStart.invoke(rule.vanillaRange);
        double policyLo = originalStart instanceof Float ? (double)(float)policy.minimum() : policy.minimum();
        double policyHi = originalStart instanceof Float ? (double)(float)policy.maximum() : policy.maximum();
        double lo = Math.max(policyLo, originalStart.doubleValue());
        double hi = Math.min(policyHi, ((Number) access.rangeEnd.invoke(rule.vanillaRange)).doubleValue());
        if (lo > hi) throw new IllegalStateException("Empty vanilla / capability range intersection: " + rule.path);
        return new NumericRange(lo, hi);
    }
    public static Object normalizeChoice(Object configurable, Object selected) {
        if (!challenge()) return selected;
        Rule rule = rules.get(configurable);
        if (rule == null || rule.enabled || rule.binding.entry == null) return selected;
        Setting setting = setting(rule);
        if (setting == null || !setting.type().startsWith("choice")) return selected;
        try { return effectiveChoice(rule.binding, configurable, setting, selected); }
        catch (Throwable error) { rule.binding.failed = true; return selected; }
    }
    private static List<Object> allowedChoices(Binding binding,Object value,Setting setting) throws Throwable {
        List<Object> allowed=new ArrayList<>();
        for(String name : setting.type().equals("choice_set") ? setting.allowedChoices() : Set.of(setting.stringValue())) allowed.add(binding.select(value,name));
        return allowed;
    }
    private static Object effectiveChoice(Binding binding,Object value,Setting setting,Object requested) throws Throwable {
        List<Object> allowed=allowedChoices(binding,value,setting);
        return allowed.contains(requested) ? requested : binding.select(value,setting.stringValue());
    }
    public static boolean routeInternalEnabled(Object value, Object requested) {
        if (!challenge() || routingEnabled.get()) return false;
        Rule rule = rules.get(value);
        if (rule == null || !rule.enabled) return false;
        routingEnabled.set(true);
        try { access.moduleEnabled.invoke(rule.binding.module, Boolean.TRUE.equals(normalizeValue(value, requested, true))); }
        catch (Throwable error) { rule.binding.failed = true; LOG.error("Growth failed to route {} lifecycle", rule.binding.name, error); }
        finally { routingEnabled.set(false); }
        return true;
    }
    public static boolean skipToggleable(Object configurable) {
        if (!challenge() || !configurable.getClass().getName().equals(STRAFE)) return false;
        Minecraft mc = Minecraft.getInstance();
        return !isActive("Speed") || mc.player == null || !mc.player.onGround()
                || mc.player.isInWaterOrBubble() || mc.player.isInLava() || mc.player.isPassenger() || mc.player.getAbilities().flying;
    }
    /** Only the learned top-level LegitHop choice may use LB's automatic ordinary jump. */
    public static boolean allowBHop(Object choice) {
        if(!challenge())return true;
        for(Binding binding:bindings.values()) if(binding.name.equals("Speed") && grant(binding)!=null
                && capability(binding).branches().contains(new Ability("speed_hop"))) {
            try {
                Object mode=binding.required("Mode"), hop=binding.select(mode,"LegitHop");
                return choice==hop && access.activeChoiceField.get(mode)==hop;
            } catch(Throwable error) { return false; }
        }
        return false;
    }
    public static Object kotlinUnit() {
        if (unit == null) throw new IllegalStateException("LB policy access not initialized");
        return unit;
    }
    public static boolean allowAuraAttack(Entity target) {
        if (!challenge()) return true;
        Minecraft mc = Minecraft.getInstance();
        Binding aura = bindings.values().stream().filter(b -> b.name.equals("KillAura")).findFirst().orElse(null);
        if (aura == null || grant(aura) == null || mc.player == null || !target.isAlive() || mc.player.getAttackStrengthScale(0F) < 1F) return false;
        try {
            float range = ((Number) normalizeValue(aura.required("Range"), access.raw.invoke(aura.required("Range")), false)).floatValue();
            Vec3 eye = mc.player.getEyePosition(); AABB box = target.getBoundingBox();
            Vec3 point = new Vec3(Math.max(box.minX, Math.min(box.maxX, eye.x)), Math.max(box.minY, Math.min(box.maxY, eye.y)), Math.max(box.minZ, Math.min(box.maxZ, eye.z)));
            return eye.distanceToSqr(point) <= (double) range * range;
        } catch (Throwable error) { return false; }
    }
    public static void tick(Minecraft mc) {
        CapabilityPolicyClient.View view = CapabilityPolicyClient.view();
        if (reconciledGeneration != view.generation()) {
            for (Binding binding : bindings.values()) {
                if (!challenge()) restoreMetadata(binding);
                else if (!binding.free) { if (!allowModule(binding.module)) disable(binding); if (binding.entry != null && !binding.failed) apply(binding); }
            }
            reconciledGeneration = view.generation();
            // LB #3530's bundled theme fetches lists/settings only when mounted; it does
            // not listen to valueChanged. Coalesce metadata changes before reloading it.
            guiRefreshPending = true;
            guiRefreshAfter = System.nanoTime() + 250_000_000L;
        }
        if (challenge()) for (Binding binding : bindings.values()) if (!allowModule(binding.module)) disable(binding);
        if (guiRefreshPending && access != null && mc.player != null && System.nanoTime() >= guiRefreshAfter) {
            guiRefreshPending = false;
            try { access.reloadClickGui(); }
            catch (Throwable error) { LOG.warn("Growth could not refresh LB ClickGUI", error); }
        }
    }
    public static void stopManagedModules() {
        for (Binding binding : bindings.values()) if (!binding.free) disable(binding);
    }
    private static void disable(Binding binding) {
        try { if (Boolean.TRUE.equals(access.raw.invoke(binding.required("Enabled")))) access.moduleEnabled.invoke(binding.module, false); }
        catch (Throwable error) { binding.failed = true; LOG.error("Growth failed to disable {} normally", binding.name, error); }
    }
    private static void apply(Binding binding) {
        if (binding.entry == null || binding.failed) return;
        try {
            for (Setting setting : capability(binding).settings().values()) {
                Object value = binding.required(setting.path()); Rule rule = rules.get(value);
                if (setting.type().startsWith("choice")) {
                    Object current = access.choiceType.isInstance(value) ? access.activeChoiceField.get(value) : access.raw.invoke(value);
                    Object selected = effectiveChoice(binding,value,setting,current);
                    List<Object> allowed = allowedChoices(binding,value,setting);
                    if (access.choiceType.isInstance(value)) {
                        // A refunded route may still be the raw active mode. Stop it through
                        // the module lifecycle before selecting the safe base; never auto-enable.
                        if(current!=selected && Boolean.TRUE.equals(access.raw.invoke(binding.required("Enabled")))) disable(binding);
                        access.choicesSet.invoke(value, new ArrayList<>((List<?>) binding.originalChoices.get(value)));
                        access.choiceSet.invoke(value, (String) access.name.invoke(selected));
                        access.choicesSet.invoke(value, new ArrayList<>(allowed));
                    } else {
                        Object old = access.raw.invoke(value);
                        if (!Objects.equals(old, selected)) access.valueSet.invoke(value, selected);
                        Object one = Array.newInstance(access.enumChoicesField.get(value).getClass().getComponentType(), allowed.size());
                        for(int i=0;i<allowed.size();i++)Array.set(one,i,allowed.get(i)); access.enumChoicesField.set(value, one);
                    }
                    access.notifyValue(value);
                }
                else {
                    Object old = access.raw.invoke(value), bounded = normalizeValue(value, old, false);
                    if (!Objects.equals(old, bounded)) access.valueSet.invoke(value, bounded);
                }
                if (rule.vanillaRange != null) {
                    NumericRange bounds = bounds(rule, setting);
                    Object prototype = access.rangeStart.invoke(rule.vanillaRange);
                    // INTEROP Gson serializes fields directly, so changing only getRange would leave stale sliders.
                    access.rangeField.set(value, access.makeRange((Number) prototype, bounds.minimum(), bounds.maximum()));
                    access.notifyValue(value);
                }
            }
        } catch (Throwable error) { binding.failed = true; LOG.error("Growth failed to apply {} settings", binding.name, error); disable(binding); }
    }
    private static void restoreMetadata(Binding binding) {
        try {
            for (Object value : binding.values.values()) {
                Rule rule = rules.get(value);
                if (rule == null || rule.enabled) continue;
                Object originalChoices = binding.originalChoices.get(value);
                if (originalChoices != null) {
                    if (access.choiceType.isInstance(value)) access.choicesSet.invoke(value, new ArrayList<>((List<?>) originalChoices));
                    else access.enumChoicesField.set(value, originalChoices);
                    access.notifyValue(value);
                }
                if (rule.vanillaRange != null && !Objects.equals(access.rangeField.get(value), rule.vanillaRange)) {
                    access.rangeField.set(value, rule.vanillaRange); access.notifyValue(value);
                }
            }
        } catch (Throwable error) { LOG.warn("Growth could not restore LB slider metadata for {}", binding.name, error); }
    }

    private static final class Binding {
        final Object module; final String name; final Entry entry; final boolean free;
        long capabilityGeneration=-1; ModuleCapability.Resolved capability;
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<Object, Object> originalChoices = new IdentityHashMap<>(); boolean failed;
        Binding(Object module, String name, Entry entry, boolean free) throws Throwable {
            this.module = module; this.name = name; this.entry = entry; this.free = free; walk(module, "");
        }
        Object required(String path) {
            Object value = values.get(path);
            if (value == null) throw new IllegalStateException("LB3530 setting missing: " + name + "/" + path);
            return value;
        }
        Object select(Object value, String name) throws Throwable {
            if (access.choiceType.isInstance(value)) {
                for (Object choice : (List<?>) originalChoices.get(value)) if (name.equals(access.name.invoke(choice))) return choice;
            } else {
                Object raw = access.raw.invoke(value);
                if (raw instanceof Enum<?> e) for (Object choice : e.getDeclaringClass().getEnumConstants())
                    if (name.equals(((Enum<?>)choice).name()) || name.equals(access.namedChoice.invoke(choice))) return choice;
            }
            throw new IllegalStateException("LB3530 choice missing: " + this.name + "/" + name);
        }
        void validate(Setting setting, Rule rule) throws Throwable {
            switch (setting.type()) {
                case "choice", "choice_set" -> { select(required(setting.path()),setting.stringValue()); allowedChoices(this,required(setting.path()),setting); }
                case "boolean" -> { if (!(rule.original instanceof Boolean)) throw new IllegalArgumentException("Not boolean: " + setting.path()); }
                case "number" -> { if (!(rule.original instanceof Number) || rule.vanillaRange == null) throw new IllegalArgumentException("Not ranged number: " + setting.path()); }
                case "range" -> { if (!access.closedRangeType.isInstance(rule.original) || rule.vanillaRange == null) throw new IllegalArgumentException("Not ranged pair: " + setting.path()); }
            }
        }
        void walk(Object configurable, String prefix) throws Throwable {
            Object children = access.raw.invoke(configurable);
            if (!(children instanceof List<?> list)) return;
            for (Object child : list) {
                if (!access.valueType.isInstance(child)) continue;
                String name = (String) access.name.invoke(child), path = prefix.isEmpty() ? name : prefix + "/" + name;
                values.put(path, child);
                if (access.choiceType.isInstance(child)) {
                    List<?> options = new ArrayList<>((List<?>)access.choiceList.invoke(child));
                    originalChoices.put(child, options);
                    for (Object choice : options) walk(choice, path + "/" + access.name.invoke(choice));
                } else {
                    if (access.enumChoiceType.isInstance(child)) originalChoices.put(child, access.enumChoicesField.get(child));
                    walk(child, path);
                }
            }
        }
    }
    private static final class Access {
        final Class<?> valueType, choiceType, rangeType, closedRangeType, enumChoiceType;
        final MethodHandle raw, name, valueSet, choiceList, choiceSet, moduleEnabled, floatRange, rangeStart, rangeEnd, namedChoice, eventCall, choicesSet, activeChoice;
        final Constructor<?> intRange, changedEvent; final Field rangeField, enumChoicesField, activeChoiceField;
        Access() throws ReflectiveOperationException {
            ClassLoader loader = LiquidBounceCapabilityAccess.class.getClassLoader();
            valueType = Class.forName(TYPES + "Value", false, loader);
            choiceType = Class.forName(TYPES + "ChoiceConfigurable", false, loader);
            enumChoiceType = Class.forName(TYPES + "ChooseListValue", false, loader);
            rangeType = Class.forName(TYPES + "RangedValue", false, loader);
            closedRangeType = Class.forName("kotlin.ranges.ClosedRange", false, loader);
            Class<?> module = Class.forName("net.ccbluex.liquidbounce.features.module.ClientModule", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            raw = lookup.unreflect(valueType.getMethod("getInner$liquidbounce")).asType(OBJECT_GET);
            name = lookup.unreflect(valueType.getMethod("getName")).asType(MethodType.methodType(String.class,Object.class));
            valueSet = lookup.unreflect(valueType.getMethod("set",Object.class));
            choiceList = lookup.unreflect(choiceType.getMethod("getChoices"));
            activeChoice = lookup.unreflect(choiceType.getMethod("getActiveChoice"));
            activeChoiceField = choiceType.getDeclaredField("activeChoice"); activeChoiceField.setAccessible(true);
            choicesSet = lookup.unreflect(choiceType.getMethod("setChoices", List.class));
            choiceSet = lookup.unreflect(choiceType.getMethod("setByString",String.class));
            moduleEnabled = lookup.unreflect(module.getMethod("setEnabled$liquidbounce",boolean.class));
            rangeStart = lookup.unreflect(closedRangeType.getMethod("getStart"));
            rangeEnd = lookup.unreflect(closedRangeType.getMethod("getEndInclusive"));
            namedChoice = lookup.unreflect(Class.forName(TYPES + "NamedChoice",false,loader).getMethod("getChoiceName"));
            Class<?> ranges = Class.forName("kotlin.ranges.RangesKt",false,loader);
            Class<?> range = Class.forName("kotlin.ranges.ClosedFloatingPointRange",false,loader);
            floatRange = lookup.findStatic(ranges,"rangeTo",MethodType.methodType(range,float.class,float.class)).asType(MethodType.methodType(Object.class,float.class,float.class));
            intRange = Class.forName("kotlin.ranges.IntRange",false,loader).getConstructor(int.class,int.class);
            rangeField = rangeType.getDeclaredField("range"); rangeField.setAccessible(true);
            enumChoicesField = enumChoiceType.getDeclaredField("choices"); enumChoicesField.setAccessible(true);
            changedEvent = Class.forName("net.ccbluex.liquidbounce.event.events.ValueChangedEvent",false,loader).getConstructor(valueType);
            Class<?> events = Class.forName("net.ccbluex.liquidbounce.event.EventManager",false,loader);
            eventCall = lookup.unreflect(events.getMethod("callEvent",Class.forName("net.ccbluex.liquidbounce.event.Event",false,loader))).bindTo(events.getField("INSTANCE").get(null));
            unit = Class.forName("kotlin.Unit",true,loader).getField("INSTANCE").get(null);
        }
        Object makeRange(Number prototype,double lo,double hi) throws Throwable {
            if (prototype instanceof Integer) return intRange.newInstance((int)Math.ceil(lo),(int)Math.floor(hi));
            if (prototype instanceof Float) return (Object)floatRange.invokeExact((float)lo,(float)hi);
            throw new IllegalArgumentException("Unsupported LB range type");
        }
        void notifyValue(Object value) throws Throwable { eventCall.invoke(changedEvent.newInstance(value)); }
        void reloadClickGui() throws ReflectiveOperationException {
            Class<?> clickGui = Class.forName("net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui", false,
                    LiquidBounceCapabilityAccess.class.getClassLoader());
            clickGui.getMethod("reloadView").invoke(clickGui.getField("INSTANCE").get(null));
        }
    }
}
