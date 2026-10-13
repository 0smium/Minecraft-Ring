import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

/** Transforms the actual pinned release. Reuses the existing offline Mixin service, not game startup. */
public final class MeteorMixinProbe {
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
        env.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("meteor-offline-mixins.json");
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        IMixinTransformer transformer = (IMixinTransformer) constructor.newInstance();
        MixinExtrasBootstrap.init();
        var expected = Map.of(
            "meteordevelopment.meteorclient.systems.modules.combat.KillAura", Map.of("rejectUnavailableHost", 1),
            "meteordevelopment.meteorclient.systems.modules.render.Nametags", Map.of("name", 1, "health", 1, "maximum", 1, "absorption", 1, "healthText", 1),
            "meteordevelopment.meteorclient.utils.entity.SortPriority", Map.of("comparableHealth", 2));
        int total = 0;
        for (var target : expected.entrySet()) {
            byte[] result = transformer.transformClass(env, target.getKey(), OfflineMixinProbe.bytes(target.getKey()));
            ClassNode node = new ClassNode(); new ClassReader(result).accept(node, 0);
            var found = new java.util.HashMap<String, Integer>();
            for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.contains("erbridge$")) {
                    String name = call.name.substring(call.name.indexOf("erbridge$") + 9);
                    found.merge(name, 1, Integer::sum); total++;
                    System.out.println("INJECTED " + target.getKey() + " " + method.name + method.desc + " -> " + name);
                }
            }
            if (!found.equals(target.getValue())) throw new AssertionError(target.getKey() + ": " + found + " expected " + target.getValue());
        }
        Class.forName("dev.ermc.bridge.client.integrations.HostEntityView", false, MeteorMixinProbe.class.getClassLoader());
        System.out.println("PASS: " + total + " real Meteor 0.5.8 injection calls and shared helper linkage");
        System.out.println("LIMIT: does not initialize client modules, start games, render tags or execute attacks");
    }
}
