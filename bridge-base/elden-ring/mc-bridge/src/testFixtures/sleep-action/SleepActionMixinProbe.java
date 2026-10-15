import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

public final class SleepActionMixinProbe {
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
        env.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("sleep-mc.json");
        Mixins.addConfiguration("sleep-common.json");
        Mixins.addConfiguration("sleep-lb.json");
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        IMixinTransformer transformer = (IMixinTransformer) constructor.newInstance();
        MixinExtrasBootstrap.init();
        Map<String, List<String>> targets = new LinkedHashMap<>();
        targets.put("net.minecraft.class_1309", List.of("suspendMovementInput", "suspendJump", "suspendUse"));
        targets.put("net.minecraft.class_1657", List.of("suspendAttack", "customFlightTravel", "customFlightLanding"));
        targets.put("net.minecraft.class_746", List.of("sleepBeforeInput", "sleepAfterInput", "sleepBeforeTravel"));
        targets.put("net.minecraft.class_310", List.of("sleepAttack", "sleepMining", "sleepUse"));
        targets.put("net.minecraft.class_636", List.of("sleepDestroy", "sleepInteract", "sleepAttack"));
        targets.put("net.ccbluex.liquidbounce.features.module.ClientModule", List.of("authorizeBeforeActivation", "executionGuard"));
        targets.put("net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura", List.of("boundEachAttack"));
        targets.put("net.ccbluex.liquidbounce.event.EventManager", List.of("sleepIgnoredEvents", "sleepOverriddenRunning"));
        targets.put("net.ccbluex.liquidbounce.utils.client.EventScheduler", List.of("sleepScheduledAction"));
        targets.put("net.ccbluex.liquidbounce.event.SequenceManager", List.of("sleepSequence"));
        int total = 0;
        for (var target : targets.entrySet()) {
            byte[] transformed = transformer.transformClass(env, target.getKey(), OfflineMixinProbe.bytes(target.getKey()));
            ClassNode node = new ClassNode(); new ClassReader(transformed).accept(node, 0);
            Set<String> hooks = new HashSet<>();
            int count = 0;
            for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.name.contains("erbridge$")) {
                    hooks.add(call.name); count++;
                }
            for (String expected : target.getValue())
                if (hooks.stream().noneMatch(name -> name.contains("erbridge$" + expected)))
                    throw new AssertionError(target.getKey() + " missing " + expected);
            if (target.getKey().equals("net.minecraft.class_636") && count != 8)
                throw new AssertionError("Expected all eight interaction boundaries, found " + count);
            if (target.getKey().equals("net.minecraft.class_1657")) {
                boolean guardedMarker = false, cancellableAttack = false, customTravel = false;
                for (MethodNode method : node.methods) {
                    if (method.name.contains("erbridge$customFlightTravel")) {
                        if (!method.desc.equals("(Z)Z")) throw new AssertionError("Precision flight must modify a read, not write abilities");
                        for (AbstractInsnNode instruction : method.instructions) {
                            if (instruction.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD)
                                throw new AssertionError("Precision travel cannot mutate Creative's stored flight state");
                            if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/ermc/bridge/api/PlayerFlight")
                                    && call.name.equals("customActive")) customTravel = true;
                        }
                    }
                    if (method.name.contains("erbridge$noteCritical")) {
                        boolean allowed = false, earlyReturn = false;
                        for (AbstractInsnNode instruction : method.instructions) {
                            if (instruction instanceof MethodInsnNode call) {
                                if (call.owner.equals("dev/ermc/bridge/api/PlayerActions") && call.name.equals("allowed")) allowed = true;
                                if (call.name.equals("noteAttack")) {
                                    if (!allowed || !earlyReturn) throw new AssertionError("Core proxy marker lacks an early action guard");
                                    guardedMarker = true;
                                }
                            } else if (allowed && instruction.getOpcode() == org.objectweb.asm.Opcodes.RETURN) earlyReturn = true;
                        }
                    }
                    if (method.name.equals("method_7324")) {
                        boolean guard = false;
                        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                            if (call.name.contains("erbridge$suspendAttack")) guard = true;
                            if (guard && call.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
                                    && call.name.equals("isCancelled")) cancellableAttack = true;
                        }
                    }
                }
                if (!guardedMarker || !cancellableAttack || !customTravel) throw new AssertionError("Core attack guard or nonmutating precision travel guard missing");
            }
            total += count;
            System.out.println("PASS " + target.getKey() + ": " + count + " production hook calls");
        }
        System.out.println("PASS sleep/custom-flight guards: " + targets.size() + " actual MC/LB classes; " + total + " hook calls");
    }
}
