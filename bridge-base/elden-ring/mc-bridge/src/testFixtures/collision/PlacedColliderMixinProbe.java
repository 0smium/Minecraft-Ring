import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Real production Mixin + real cached MC class, without class initialization or a world. */
public final class PlacedColliderMixinProbe {
    private static final String MIXIN = "dev.ermc.bridge.mixin.PlacedBlockColliderRevisionMixin";
    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        for (var list : Arrays.asList(visible, invisible)) if (list != null)
            for (var annotation : list) if (annotation.desc.equals(desc)) return annotation;
        throw new AssertionError("Missing annotation " + desc);
    }
    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation value " + key);
    }
    private static int returns(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() == Opcodes.ARETURN) count++;
        return count;
    }
    public static void main(String[] args) throws Exception {
        ClassNode mixin = read(OfflineMixinProbe.bytes(MIXIN));
        AnnotationNode targetAnnotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations,
            "Lorg/spongepowered/asm/mixin/Mixin;");
        var targets = (List<?>) value(targetAnnotation, "value");
        if (targets.size() != 1 || !(targets.getFirst() instanceof Type targetType))
            throw new AssertionError("Expected one class literal @Mixin target");
        String target = targetType.getClassName();
        if (!target.startsWith("net.minecraft.class_")) throw new AssertionError("Use the remapped production JAR: " + target);
        MethodNode handler = mixin.methods.stream().filter(method -> method.name.equals("erbridge$placedColliderChanged"))
            .findFirst().orElseThrow();
        AnnotationNode injection = annotation(handler.visibleAnnotations, handler.invisibleAnnotations,
            "Lorg/spongepowered/asm/mixin/injection/Inject;");
        var selectors = (List<?>) value(injection, "method");
        if (selectors.size() != 1) throw new AssertionError("Expected one setBlockState selector");
        String selector = (String) selectors.getFirst();
        var points = (List<?>) value(injection, "at");
        if (points.size() != 1 || !value((AnnotationNode) points.getFirst(), "value").equals("RETURN"))
            throw new AssertionError("Expected RETURN injection");
        int descriptor = selector.indexOf('(');
        String methodName = descriptor < 0 ? selector : selector.substring(0, descriptor);
        String methodDescriptor = descriptor < 0 ? null : selector.substring(descriptor);
        ClassNode original = read(OfflineMixinProbe.bytes(target));
        var methods = original.methods.stream().filter(method -> method.name.equals(methodName)
            && (methodDescriptor == null || method.desc.equals(methodDescriptor))).toList();
        if (methods.size() != 1) throw new AssertionError("Actual MC target method count=" + methods.size() + " for " + selector);
        MethodNode originalMethod = methods.getFirst();
        int expectedReturns = returns(originalMethod);
        if (expectedReturns == 0) throw new AssertionError("Expected reference-returning setBlockState method");
        Type[] targetArgs = Type.getArgumentTypes(originalMethod.desc), handlerArgs = Type.getArgumentTypes(handler.desc);
        if (handlerArgs.length != targetArgs.length + 1)
            throw new AssertionError("Handler arguments do not match actual target");
        for (int i = 0; i < targetArgs.length; i++) if (!targetArgs[i].equals(handlerArgs[i]))
            throw new AssertionError("Handler argument " + i + " differs from actual target");

        MixinBootstrap.init();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
        env.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("block-collider-offline-mixins.json");
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        IMixinTransformer transformer = (IMixinTransformer) constructor.newInstance();
        ClassNode transformed = read(transformer.transformClass(env, target, OfflineMixinProbe.bytes(target)));
        MethodNode result = transformed.methods.stream().filter(method -> method.name.equals(originalMethod.name)
            && method.desc.equals(originalMethod.desc)).findFirst().orElseThrow();
        int calls = 0, helperCalls = 0, unexpectedCalls = 0;
        for (MethodNode method : transformed.methods) for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.name.contains(handler.name)) {
                if (method == result && call.owner.equals(transformed.name) && call.desc.equals(handler.desc)) calls++;
                else unexpectedCalls++;
            }
            if (method.name.contains(handler.name)
                    && call.owner.equals("dev/ermc/bridge/collision/PlacedBlockCollisionSnapshots")
                    && call.name.equals("blockChanged")) helperCalls++;
        }
        if (calls != expectedReturns || returns(result) != expectedReturns || unexpectedCalls != 0 || helperCalls != 1)
            throw new AssertionError("RETURN hooks=" + calls + " expected=" + expectedReturns
                + " resulting returns=" + returns(result) + " unexpected=" + unexpectedCalls + " helper calls=" + helperCalls);
        System.out.println("TARGET from production @Mixin: " + target);
        System.out.println("INJECTED " + result.name + result.desc + " RETURN hooks=" + calls + " cache invalidation calls=" + helperCalls);
        System.out.println("PASS: every actual MC setBlockState return invokes the remapped revision hook");
        System.out.println("LIMIT: bytecode transformation only; no Fabric startup, block placement, world lifecycle or native collision execution");
    }
}
