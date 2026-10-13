import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Verifies the production intermediary accessor against the actual cached Minecraft class. */
public final class HostEffectMixinProbe {
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
        env.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("host-effect-offline-mixins.json");
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        IMixinTransformer transformer = (IMixinTransformer) constructor.newInstance();
        String target = "net.minecraft.class_1293";
        byte[] transformed = transformer.transformClass(env, target, OfflineMixinProbe.bytes(target));
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        if (!node.interfaces.contains("dev/ermc/bridge/mixin/MobEffectInstanceAccessor")) {
            throw new AssertionError("Accessor interface was not injected");
        }
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("erbridge$getHiddenEffect") || !method.desc.equals("()Lnet/minecraft/class_1293;")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals(node.name) && field.name.equals("field_21830")
                        && field.desc.equals("Lnet/minecraft/class_1293;")) matches++;
            }
        }
        if (matches != 1) throw new AssertionError("Expected one accessor read of hiddenEffect, found " + matches);
        System.out.println("PASS: production accessor injects one hiddenEffect read into actual MC 1.21.1 class");
        System.out.println("LIMIT: no Fabric launcher, player lifecycle or game startup");
    }
}
