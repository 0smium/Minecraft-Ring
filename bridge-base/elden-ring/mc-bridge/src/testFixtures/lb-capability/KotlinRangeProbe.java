import java.lang.invoke.*;
import java.lang.reflect.Modifier;

/** Actual Kotlin 2.1.0 public access; no LB module singleton or game initialization. */
public final class KotlinRangeProbe {
    public static void main(String[] args) throws Throwable {
        ClassLoader loader = KotlinRangeProbe.class.getClassLoader();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> ranges = Class.forName("kotlin.ranges.RangesKt", false, loader);
        Class<?> range = Class.forName("kotlin.ranges.ClosedFloatingPointRange", false, loader);
        MethodHandle factory = lookup.findStatic(ranges, "rangeTo", MethodType.methodType(range, float.class, float.class))
                .asType(MethodType.methodType(Object.class, float.class, float.class));
        Object full = (Object) factory.invokeExact(1F, 1F);
        if (!range.isInstance(full)) throw new AssertionError("Not Kotlin's floating range");
        Class<?> closed = Class.forName("kotlin.ranges.ClosedRange", false, loader);
        MethodHandle start = lookup.unreflect(closed.getMethod("getStart")).asType(MethodType.methodType(Object.class, Object.class));
        MethodHandle end = lookup.unreflect(closed.getMethod("getEndInclusive")).asType(MethodType.methodType(Object.class, Object.class));
        if (!Float.valueOf(1F).equals((Object) start.invokeExact(full))
                || !Float.valueOf(1F).equals((Object) end.invokeExact(full))) throw new AssertionError("Wrong cooldown endpoints");
        if (Modifier.isPublic(full.getClass().getModifiers())) throw new AssertionError("Expected inaccessible Kotlin implementation fixture");
        try {
            full.getClass().getConstructor(float.class, float.class).newInstance(1F, 1F);
            throw new AssertionError("Old constructor unexpectedly accessible");
        } catch (IllegalAccessException expected) { }
        System.out.println("PASS old public-constructor access fails; public RangesKt factory builds exact Kotlin 1F..1F");
        // Resolve every other production public-access owner without initializing Speed/Aura.
        Class<?> value = Class.forName("net.ccbluex.liquidbounce.config.types.Value", false, loader);
        Class<?> choice = Class.forName("net.ccbluex.liquidbounce.config.types.ChoiceConfigurable", false, loader);
        Class<?> module = Class.forName("net.ccbluex.liquidbounce.features.module.ClientModule", false, loader);
        lookup.unreflect(value.getMethod("getInner$liquidbounce"));
        lookup.unreflect(value.getMethod("getName"));
        lookup.unreflect(value.getMethod("set", Object.class));
        lookup.unreflect(choice.getMethod("getChoices"));
        lookup.unreflect(choice.getMethod("setByString", String.class));
        lookup.unreflect(module.getMethod("setEnabled$liquidbounce", boolean.class));
        for (String name : new String[] {
                "net.ccbluex.liquidbounce.features.module.modules.movement.speed.ModuleSpeed",
                "net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura",
                "kotlin.Unit" }) {
            Class<?> type = Class.forName(name, false, loader);
            lookup.unreflectGetter(type.getField("INSTANCE"));
        }
        System.out.println("PASS remaining Access getters/setters and public INSTANCE field handles resolve without module initialization");
        System.out.println("LIMIT: actual module registration, GUI, cleanup and game execution still require live validation.");
    }
}
