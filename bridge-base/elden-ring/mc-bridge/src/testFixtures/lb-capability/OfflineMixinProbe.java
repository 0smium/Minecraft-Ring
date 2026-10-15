import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

/** Offline bytecode transformation only: no Minecraft launcher, game main or target instances. */
public final class OfflineMixinProbe {
  static final ClassLoader CL = OfflineMixinProbe.class.getClassLoader();
  static byte[] bytes(String name) throws IOException {
    try (InputStream in = CL.getResourceAsStream(name.replace('.', '/') + ".class")) {
      if (in == null) throw new FileNotFoundException(name);
      return in.readAllBytes();
    }
  }
  public static void main(String[] args) throws Exception {
    MixinBootstrap.init();
    MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
    env.setSide(MixinEnvironment.Side.CLIENT);
    Mixins.addConfiguration("offline-mixins.json");
    var ctor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
    ctor.setAccessible(true);
    IMixinTransformer transformer = (IMixinTransformer) ctor.newInstance();
    MixinExtrasBootstrap.init(); for (String helper : new String[] {"dev.ermc.bridge.client.integrations.CapabilityPolicyClient", "dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess"}) {transformer.transformClass(env, helper, bytes(helper)); Class.forName(helper, true, CL); System.out.println("PASS normal helper classload outside mixin package: " + helper);}
    String[] targets = {
 "net.ccbluex.liquidbounce.config.types.Value",
 "net.ccbluex.liquidbounce.config.types.ChoiceConfigurable",
 "net.ccbluex.liquidbounce.features.module.ClientModule",
 "net.ccbluex.liquidbounce.features.module.ModuleManager",
 "net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura",
 "net.ccbluex.liquidbounce.config.types.ToggleableConfigurable",
 "net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes.SpeedBHopBase",
 "net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.client.ModuleFunctionsKt",
 "net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.client.ModuleRequest"
}; int[] expectedCalls = {5,2,2,1,1,1,1,1,1}; int targetIndex = 0;
    for (String target : targets) {
      byte[] original = bytes(target);
      byte[] result = transformer.transformClass(env, target, original);
      ClassNode node = new ClassNode();
      new ClassReader(result).accept(node, 0);
      int calls = 0;
      for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof MethodInsnNode call && call.name.contains("erbridge$")) {
          calls++;
          System.out.println("INJECTED " + target + " caller=" + method.name + method.desc + " hook=" + call.name + call.desc);
        }
      }
      if (calls != expectedCalls[targetIndex++]) throw new AssertionError(target + " injected hook count=" + calls);
      System.out.println("PASS " + target + " bytes " + original.length + " -> " + result.length);
    }
  }
  public static final class Service extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider {
    public String getName() { return "offline-bytecode-probe"; }
    public boolean isValid() { return true; }
    public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
    public IClassProvider getClassProvider() { return this; }
    public IClassBytecodeProvider getBytecodeProvider() { return this; }
    public ITransformerProvider getTransformerProvider() { return null; }
    public IClassTracker getClassTracker() { return null; }
    public IMixinAuditTrail getAuditTrail() { return null; }
    public IFeatureValidator getFeatureValidator() { return null; }
    public IAdviceProvider getAdviceProvider() { return null; }
    public Collection<String> getPlatformAgents() { return List.of(); }
    public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("offline-probe"); }
    public InputStream getResourceAsStream(String name) { return CL.getResourceAsStream(name); }
    public URL[] getClassPath() { return new URL[0]; }
    public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException { return Class.forName(name, initialize, CL); }
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return findClass(name, initialize); }
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, false, 0); }
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException { return getClassNode(name, runTransformers, 0); }
    public ClassNode getClassNode(String name, boolean runTransformers, int flags) throws ClassNotFoundException, IOException {
      ClassNode node = new ClassNode(); new ClassReader(bytes(name)).accept(node, flags); return node;
    }
  }
  public static final class PropertiesService implements IGlobalPropertyService {
    static final Map<IPropertyKey, Object> VALUES = new HashMap<>();
    record Key(String name) implements IPropertyKey { }
    public IPropertyKey resolveKey(String name) { return new Key(name); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T) VALUES.get(key); }
    public void setProperty(IPropertyKey key, Object value) { VALUES.put(key, value); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key, T fallback) { return (T) VALUES.getOrDefault(key, fallback); }
    public String getPropertyString(IPropertyKey key, String fallback) { return String.valueOf(VALUES.getOrDefault(key, fallback)); }
  }
}
