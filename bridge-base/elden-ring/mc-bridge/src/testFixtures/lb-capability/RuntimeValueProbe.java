import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import dev.ermc.bridge.api.*;

/** Executes transformed real Value setters; no LB modules, Minecraft singleton, or game main. */
public final class RuntimeValueProbe {
  static IMixinTransformer transformer;
  static MixinEnvironment environment;
  static final ClassLoader parent = RuntimeValueProbe.class.getClassLoader();
  static final class Isolated extends ClassLoader {
    Isolated() { super(parent); }
    @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith("net.ccbluex.liquidbounce.") && !name.startsWith("dev.ermc.bridge.client.integrations."))
        return super.loadClass(name, resolve);
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) try (InputStream stream = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
        if (stream == null) throw new ClassNotFoundException(name);
        byte[] original = stream.readAllBytes();
        byte[] transformed = transformer.transformClass(environment, name, original);
        loaded = defineClass(name, transformed, 0, transformed.length);
      } catch (IOException error) { throw new ClassNotFoundException(name, error); }
      if (resolve) resolveClass(loaded);
      return loaded;
    }
  }
  static Object constant(Class<?> type, String name) {
    for (Object value : type.getEnumConstants()) if (((Enum<?>) value).name().equals(name)) return value;
    throw new AssertionError(name);
  }
  static void field(Class<?> type, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
  }
  static CapabilitySnapshot snapshot(int rank, double speed, double range, long revision) {
    var binding = new CharacterBinding(new UUID(1,2), new UUID(3,4), new UUID(5,6));
    var grants = new EnumMap<CapabilitySnapshot.Ability, CapabilitySnapshot.Grant>(CapabilitySnapshot.Ability.class);
    grants.put(CapabilitySnapshot.Ability.SPEED, rank == 0 ? new CapabilitySnapshot.Grant(0,true,Set.of(),Map.of())
      : new CapabilitySnapshot.Grant(rank,true,Set.of("ground_custom"),Map.of("speed",new CapabilitySnapshot.NumericRange(.1,speed))));
    grants.put(CapabilitySnapshot.Ability.AURA, rank == 0 ? new CapabilitySnapshot.Grant(0,true,Set.of(),Map.of())
      : new CapabilitySnapshot.Grant(rank,true,Set.of("single_target"),Map.of("range",new CapabilitySnapshot.NumericRange(1,range))));
    return new CapabilitySnapshot(binding, revision, grants);
  }
  static void equalsFloat(float expected, Object actual) {
    if (!(actual instanceof Float f) || Math.abs(f-expected)>.00001F) throw new AssertionError(expected + " != " + actual);
  }
  public static void main(String[] args) throws Throwable {
    MixinBootstrap.init(); environment = MixinEnvironment.getDefaultEnvironment(); environment.setSide(MixinEnvironment.Side.CLIENT);
    Mixins.addConfiguration("value-runtime-mixins.json");
    var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
    constructor.setAccessible(true); transformer = (IMixinTransformer)constructor.newInstance();
    ClassLoader loader = new Isolated();
    Class<?> value = Class.forName("net.ccbluex.liquidbounce.config.types.Value", true, loader);
    Class<?> valueType = Class.forName("net.ccbluex.liquidbounce.config.types.ValueType", true, loader);
    Class<?> listType = Class.forName("net.ccbluex.liquidbounce.config.types.ListValueType", true, loader);
    Constructor<?> makeValue = value.getConstructor(String.class,Object.class,valueType,listType,boolean.class);
    Object speed = makeValue.newInstance("Speed",10F,constant(valueType,"FLOAT"),constant(listType,"None"),false);
    Object range = makeValue.newInstance("Range",8F,constant(valueType,"FLOAT"),constant(listType,"None"),false);
    Object enabled = makeValue.newInstance("Enabled",false,constant(valueType,"BOOLEAN"),constant(listType,"None"),false);
    Class<?> helper = Class.forName("dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess", true, loader);
    Class<?> gateway = Class.forName("dev.ermc.bridge.client.integrations.CapabilityPolicyClient", true, loader);
    Class<?> rule = Class.forName("dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess$Rule", true, loader);
    Constructor<?> makeRule = rule.getDeclaredConstructor(CapabilitySnapshot.Ability.class,String.class,Object.class,boolean.class);
    makeRule.setAccessible(true);
    var rules = new IdentityHashMap<Object,Object>();
    rules.put(speed,makeRule.newInstance(CapabilitySnapshot.Ability.SPEED,"speed",null,false));
    rules.put(range,makeRule.newInstance(CapabilitySnapshot.Ability.AURA,"range",null,false));
    rules.put(enabled,makeRule.newInstance(CapabilitySnapshot.Ability.SPEED,null,null,true));
    field(helper,"rules",rules);
    Method get = value.getMethod("get"), set = value.getMethod("set",Object.class), raw = value.getMethod("getInner$liquidbounce");
    Method publish = gateway.getMethod("publish",boolean.class,CapabilitySnapshot.class);
    equalsFloat(10F,get.invoke(speed)); set.invoke(speed,12F); equalsFloat(12F,get.invoke(speed));
    System.out.println("PASS free mode preserves real Value writes/reads");
    publish.invoke(null,true,null); set.invoke(enabled,true);
    if (!Boolean.FALSE.equals(raw.invoke(enabled))) throw new AssertionError("pending policy activated module");
    publish.invoke(null,true,snapshot(1,.30,2.5,1));
    equalsFloat(.30F,get.invoke(speed)); equalsFloat(2.5F,get.invoke(range));
    set.invoke(speed,99F); set.invoke(range,8F); equalsFloat(.30F,raw.invoke(speed)); equalsFloat(2.5F,raw.invoke(range));
    value.getMethod("setInner$liquidbounce",Object.class).invoke(speed,20F); equalsFloat(.30F,raw.invoke(speed));
    value.getMethod("restore").invoke(speed); equalsFloat(.30F,raw.invoke(speed));
    Object gson = Class.forName("com.google.gson.Gson",true,parent).getConstructor().newInstance();
    Object json = Class.forName("com.google.gson.JsonPrimitive",true,parent).getConstructor(Number.class).newInstance(12);
    value.getMethod("deserializeFrom",gson.getClass(),Class.forName("com.google.gson.JsonElement",false,parent)).invoke(speed,gson,json);
    equalsFloat(.30F,raw.invoke(speed));
    System.out.println("PASS actual setters, direct inner, restore, Gson config import and execution reads clamp rank-I .30/2.5");
    try { set.invoke(speed,Float.NaN); throw new AssertionError("NaN accepted"); }
    catch (InvocationTargetException rejected) { if (!(rejected.getCause() instanceof IllegalArgumentException)) throw rejected; }
    publish.invoke(null,true,snapshot(2,.33,3,2)); set.invoke(speed,.32F); equalsFloat(.32F,raw.invoke(speed));
    publish.invoke(null,true,snapshot(1,.30,2.5,3)); equalsFloat(.30F,get.invoke(speed));
    publish.invoke(null,true,snapshot(2,.36,3.5,2)); equalsFloat(.30F,get.invoke(speed));
    publish.invoke(null,true,snapshot(0,0,0,4)); set.invoke(enabled,true);
    if (!Boolean.FALSE.equals(raw.invoke(enabled))) throw new AssertionError("locked module activated");
    gateway.getMethod("clear").invoke(null); set.invoke(speed,9F); equalsFloat(9F,get.invoke(speed));
    System.out.println("PASS nonfinite rejected, downgrade read guard, stale revision rejected, locked activation denied, free mode restored");
    System.out.println("LIMIT: no real module singleton, lifecycle cleanup, GUI, movement/attack, or game startup was executed.");
  }
}
