package dev.ermc.bridge.client.action;

import java.lang.reflect.Method;

/** Optional, fixed #3530 adapter: execution state is separate from LB's Enabled preference. */
public final class LiquidBounceActionLock {
    private record Access(Method category, Method parent, Method owner, Method module) {}
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            return new Access(method(type, "getCategory"), method(type, "parent"),
                    method(type, "getHandlerClass"), method(type, "getModule"));
        }
    };
    private LiquidBounceActionLock() {}

    private static Method method(Class<?> type, String name) {
        try { return type.getMethod(name); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    public static boolean blocksModule(Object listener) {
        if (!ActionLockClient.blocksActions()) return false;
        // Choices and toggleable configurations delegate running to their parent module.
        for (int depth = 0; listener != null && depth < 32; depth++) {
            Access access = ACCESS.get(listener.getClass());
            try {
                if (access.category != null) {
                    String category = ((Enum<?>) access.category.invoke(listener)).name();
                    return !category.equals("RENDER") && !category.equals("CLIENT");
                }
                if (access.parent == null) return false;
                Object parent = access.parent.invoke(listener);
                if (parent == listener) return true;
                listener = parent;
            } catch (ReflectiveOperationException failure) {
                // A broken optional adapter must not let a known behavior listener run asleep.
                return true;
            }
        }
        return listener != null;
    }

    public static boolean blocksIgnoredEvent(Object hook, Object event) {
        if (!ActionLockClient.blocksActions()) return false;
        String name = event.getClass().getSimpleName();
        // In #3530 these ignored tick handlers only cancel/restart tickHandler sequences or
        // decrement Velocity's pause counter. Keeping them alive lets wake resume Enabled modules.
        if (name.equals("GameTickEvent") || name.equals("DisconnectEvent") || name.equals("WorldChangeEvent")) return false;
        try { return blocksModule(ACCESS.get(hook.getClass()).owner.invoke(hook)); }
        catch (ReflectiveOperationException failure) { return true; }
    }

    public static boolean blocksScheduledTask(Object task) {
        if (!ActionLockClient.blocksActions()) return false;
        try { return blocksModule(ACCESS.get(task.getClass()).module.invoke(task)); }
        catch (ReflectiveOperationException failure) { return true; }
    }
}
