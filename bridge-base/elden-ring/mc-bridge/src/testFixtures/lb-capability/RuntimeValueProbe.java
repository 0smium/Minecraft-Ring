import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;

/** Real transformed LB Value/RangedValue and Kotlin ranges, with an explicit in-memory binding fixture. */
public final class RuntimeValueProbe {
  static IMixinTransformer transformer; static MixinEnvironment environment;
  static final ClassLoader parent = RuntimeValueProbe.class.getClassLoader();
  static final class Isolated extends ClassLoader {
    Isolated() { super(parent); }
    @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith("net.ccbluex.liquidbounce.") && !name.startsWith("dev.ermc.bridge.client.integrations.")) return super.loadClass(name, resolve);
      Class<?> loaded=findLoadedClass(name);
      if(loaded==null)try(InputStream stream=parent.getResourceAsStream(name.replace('.','/')+".class")) {
        if(stream==null)throw new ClassNotFoundException(name);
        byte[] original=stream.readAllBytes(), transformed=transformer.transformClass(environment,name,original);
        loaded=defineClass(name,transformed,0,transformed.length);
      }catch(IOException e){throw new ClassNotFoundException(name,e);}
      if(resolve)resolveClass(loaded);return loaded;
    }
  }
  static Object constant(Class<?> type,String name){for(Object v:type.getEnumConstants())if(((Enum<?>)v).name().equals(name))return v;throw new AssertionError(name);}
  static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
  static CapabilitySnapshot snapshot(int rank,long revision){
    var b=new CharacterBinding(new UUID(1,2),new UUID(3,4),new UUID(5,6));
    Map<CapabilitySnapshot.Ability,CapabilitySnapshot.Grant> grants=new HashMap<>();
    for(var entry:AbilityCatalog.instance().entries()){
      int n=Math.min(rank,entry.ranks().size());var r=entry.ranks().get(Math.max(0,n-1));
      grants.put(entry.id(),new CapabilitySnapshot.Grant(n,true,n==0?Set.of():r.modes(),n==0?Map.of():r.limits()));
    }
    return new CapabilitySnapshot(b,revision,grants);
  }
  static void eq(float expected,Object actual){if(!(actual instanceof Float f)||Math.abs(f-expected)>.00001F)throw new AssertionError(expected+" != "+actual);}
  static Class<?> value,rangeType,configurable,helper,binding,rule;static Constructor<?> makeValue,makeRange,makeConfig,makeBinding,makeRule;
  static Method raw,get,set,publish;static Map<Object,Object> rules=new IdentityHashMap<>(),bindings=new IdentityHashMap<>();
  static Object ranged(String name,Object value,String type,Object range)throws Exception{return makeRange.newInstance(name,value,range,"",constant(rangeType,type));}
  static Object bind(String module,String id,List<Object> values)throws Exception{
    boolean hasEnabled=false;for(Object v:values)if(value.getMethod("getName").invoke(v).equals("Enabled"))hasEnabled=true;
    if(!hasEnabled)values.add(makeValue.newInstance("Enabled",false,constant(rangeType,"BOOLEAN"),constant(makeValue.getParameterTypes()[3],"None"),false));
    Object fake=makeConfig.newInstance(module,values,constant(rangeType,"CONFIGURABLE"),false);
    Object b=makeBinding.newInstance(fake,module,id==null?null:AbilityCatalog.instance().entry(new CapabilitySnapshot.Ability(id)),false);
    bindings.put(fake,b);return b;
  }
  @SuppressWarnings("unchecked") static void rule(Object b,Object v,String path,boolean enabled,Object vanilla)throws Exception{
    ((Map<String,Object>)field(binding,"values").get(b)).put(path,v);
    rules.put(v,makeRule.newInstance(b,path,enabled,raw.invoke(v),vanilla));
  }
  public static void main(String[] args)throws Throwable{
    MixinBootstrap.init();environment=MixinEnvironment.getDefaultEnvironment();environment.setSide(MixinEnvironment.Side.CLIENT);
    Mixins.addConfiguration("value-runtime-mixins.json");var c=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();c.setAccessible(true);transformer=(IMixinTransformer)c.newInstance();
    ClassLoader loader=new Isolated();
    value=Class.forName("net.ccbluex.liquidbounce.config.types.Value",true,loader);rangeType=Class.forName("net.ccbluex.liquidbounce.config.types.ValueType",true,loader);
    Class<?> listType=Class.forName("net.ccbluex.liquidbounce.config.types.ListValueType",true,loader), closed=Class.forName("kotlin.ranges.ClosedRange",false,loader);
    makeValue=value.getConstructor(String.class,Object.class,rangeType,listType,boolean.class);
    makeRange=Class.forName("net.ccbluex.liquidbounce.config.types.RangedValue",true,loader).getConstructor(String.class,Object.class,closed,String.class,rangeType);
    configurable=Class.forName("net.ccbluex.liquidbounce.config.types.Configurable",true,loader);makeConfig=configurable.getConstructor(String.class,List.class,rangeType,boolean.class);
    helper=Class.forName("dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess",true,loader);
    Class<?> access=Class.forName(helper.getName()+"$Access",true,loader);var ac=access.getDeclaredConstructor();ac.setAccessible(true);field(helper,"access").set(null,ac.newInstance());
    binding=Class.forName(helper.getName()+"$Binding",true,loader);makeBinding=binding.getDeclaredConstructor(Object.class,String.class,AbilityCatalog.Entry.class,boolean.class);makeBinding.setAccessible(true);
    rule=Class.forName(helper.getName()+"$Rule",true,loader);makeRule=rule.getDeclaredConstructor(binding,String.class,boolean.class,Object.class,Object.class);makeRule.setAccessible(true);
    raw=value.getMethod("getInner$liquidbounce");get=value.getMethod("get");set=value.getMethod("set",Object.class);
    var gateway=Class.forName("dev.ermc.bridge.client.integrations.CapabilityPolicyClient",true,loader);publish=gateway.getMethod("publish",boolean.class,CapabilitySnapshot.class);
    var ranges=Class.forName("kotlin.ranges.RangesKt",false,loader);java.lang.invoke.MethodHandle floatRange=java.lang.invoke.MethodHandles.publicLookup().findStatic(ranges,"rangeTo",java.lang.invoke.MethodType.methodType(Class.forName("kotlin.ranges.ClosedFloatingPointRange",false,loader),float.class,float.class));
    Object speedRange=floatRange.invoke(.1F,10F), speed=ranged("Speed",10F,"FLOAT",speedRange);
    Object speedBinding=bind("Speed","speed",new ArrayList<>(List.of(speed)));rule(speedBinding,speed,"Mode/Custom/Strafe/Speed",false,speedRange);
    var ir=Class.forName("kotlin.ranges.IntRange",false,loader).getConstructor(int.class,int.class);
    Object delayRange=ir.newInstance(0,40), delay=ranged("Delay",ir.newInstance(0,0),"INT_RANGE",delayRange);
    Object scaffoldBinding=bind("Scaffold","scaffold",new ArrayList<>(List.of(delay)));rule(scaffoldBinding,delay,"Delay",false,delayRange);
    Object enabled=makeValue.newInstance("Enabled",false,constant(rangeType,"BOOLEAN"),constant(listType,"None"),false);
    Object unknownBinding=bind("Uncatalogued",null,new ArrayList<>(List.of(enabled)));rule(unknownBinding,enabled,"Enabled",true,null);
    Class<?> jumpEnum=Class.forName("net.ccbluex.liquidbounce.features.module.modules.movement.ModuleAirJump$Mode",true,loader);
    Class<?> namedChoice=Class.forName("net.ccbluex.liquidbounce.config.types.NamedChoice",false,loader);
    Class<?> chooseList=Class.forName("net.ccbluex.liquidbounce.config.types.ChooseListValue",true,loader);
    Object doubleJump=constant(jumpEnum,"DOUBLE_JUMP"),freely=constant(jumpEnum,"JUMP_FREELY"),ghost=constant(jumpEnum,"GHOST_BLOCK");
    Object jumpMode=chooseList.getConstructor(String.class,namedChoice,java.lang.reflect.Array.newInstance(namedChoice,0).getClass())
        .newInstance("Mode",doubleJump,jumpEnum.getEnumConstants());
    Object jumpBinding=bind("AirJump","airjump",new ArrayList<>(List.of(jumpMode)));rule(jumpBinding,jumpMode,"Mode",false,null);
    field(helper,"rules").set(null,rules);field(helper,"bindings").set(null,bindings);
    eq(10F,get.invoke(speed));set.invoke(speed,12F);eq(12F,get.invoke(speed));
    publish.invoke(null,true,null);set.invoke(enabled,true);if(!Boolean.FALSE.equals(raw.invoke(enabled)))throw new AssertionError("unknown enabled");
    publish.invoke(null,true,snapshot(1,1));eq(.30F,get.invoke(speed));set.invoke(speed,99F);eq(.30F,raw.invoke(speed));
    value.getMethod("setInner$liquidbounce",Object.class).invoke(speed,20F);eq(.30F,raw.invoke(speed));value.getMethod("restore").invoke(speed);eq(.30F,raw.invoke(speed));
    Object gson=Class.forName("com.google.gson.Gson",true,parent).getConstructor().newInstance(),json=Class.forName("com.google.gson.JsonPrimitive",true,parent).getConstructor(Number.class).newInstance(12);
    value.getMethod("deserializeFrom",gson.getClass(),Class.forName("com.google.gson.JsonElement",false,parent)).invoke(speed,gson,json);eq(.30F,raw.invoke(speed));
    set.invoke(delay,ir.newInstance(0,99));Object dr=raw.invoke(delay);if(!dr.equals(ir.newInstance(4,40)))throw new AssertionError("int range/original intersection "+dr);
    try{set.invoke(speed,Float.NaN);throw new AssertionError("NaN accepted");}catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException))throw e;}
    set.invoke(jumpMode,freely);if(get.invoke(jumpMode)!=doubleJump)throw new AssertionError("rank-I free jump permitted");
    publish.invoke(null,true,snapshot(2,2));set.invoke(speed,.32F);eq(.32F,raw.invoke(speed));
    if(get.invoke(jumpMode)!=doubleJump)throw new AssertionError("upgrade changed legal double jump preference");
    set.invoke(jumpMode,freely);if(get.invoke(jumpMode)!=freely)throw new AssertionError("rank-II free jump denied");
    set.invoke(jumpMode,ghost);if(get.invoke(jumpMode)!=doubleJump)throw new AssertionError("ghost mode allowed");
    set.invoke(jumpMode,freely);publish.invoke(null,true,snapshot(1,3));eq(.30F,get.invoke(speed));
    if(get.invoke(jumpMode)!=doubleJump)throw new AssertionError("downgrade kept unlimited jump execution");
    publish.invoke(null,true,snapshot(3,2));eq(.30F,get.invoke(speed));
    var narrowBase=snapshot(1,4);var narrowGrants=new HashMap<>(narrowBase.abilities());var oldSpeed=narrowGrants.get(CapabilitySnapshot.Ability.SPEED);
    var narrowLimits=new HashMap<>(oldSpeed.numericLimits());narrowLimits.put("speed",new CapabilitySnapshot.NumericRange(.1,.2));
    narrowGrants.put(CapabilitySnapshot.Ability.SPEED,new CapabilitySnapshot.Grant(1,true,oldSpeed.allowedModes(),narrowLimits));
    publish.invoke(null,true,new CapabilitySnapshot(narrowBase.binding(),4,narrowGrants));set.invoke(speed,9F);eq(.2F,raw.invoke(speed));
    System.out.println("PASS stricter server override remains unlocked and governs actual reads/writes");
    System.out.println("PASS actual AirJump enum: rank-I double jump; upgrade preserves preference; rank-II permits free jump; ghost rejected; downgrade guarded");
    // Test real metadata field that INTEROP Gson reads, without a Minecraft singleton or browser.
    var rf=Class.forName("net.ccbluex.liquidbounce.config.types.RangedValue",false,loader).getDeclaredField("range");rf.setAccessible(true);rf.set(speed,floatRange.invoke(.1F,.3F));
    if(!speed.getClass().getMethod("getRange").invoke(speed).equals(floatRange.invoke(.1F,.3F)))throw new AssertionError("range metadata write");
    gateway.getMethod("clear").invoke(null);set.invoke(speed,9F);eq(9F,get.invoke(speed));
    System.out.println("PASS real LB set/get/inner/restore/Gson import; float and IntRange type preservation; vanilla intersection; unknown locked; nonfinite rejection; downgrade/stale revision; free mode; metadata access");
    GuiPolicyProbe.run(loader);
    System.out.println("LIMIT: binding fixtures are explicit, not real module singleton initialization. No game, GUI, networking or movement executed.");
  }
}
