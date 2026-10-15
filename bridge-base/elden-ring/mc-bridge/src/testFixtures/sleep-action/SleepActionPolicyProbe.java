import dev.ermc.bridge.client.action.ActionLockClient;
import dev.ermc.bridge.client.action.LiquidBounceActionLock;
import dev.ermc.bridge.client.PolicyHeartbeatLease;

public final class SleepActionPolicyProbe {
    public enum Category {COMBAT, MOVEMENT, PLAYER, WORLD, EXPLOIT, MISC, FUN, RENDER, CLIENT}
    public record Module(Category category) { public Category getCategory() { return category; } }
    public record Child(Object owner) { public Object parent() { return owner; } }
    public record Hook(Object owner) { public Object getHandlerClass() { return owner; } }
    public record Task(Object owner) { public Object getModule() { return owner; } }
    public static final class GameTickEvent {}
    public static final class PacketEvent {}
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }

    public static void main(String[] args) throws Exception {
        var state = ActionLockClient.class.getDeclaredField("sleeping"); state.setAccessible(true);
        state.setBoolean(null, true);
        for (Category category : Category.values()) {
            boolean expected = category != Category.RENDER && category != Category.CLIENT;
            var module = new Module(category);
            check(LiquidBounceActionLock.blocksModule(module) == expected, "category " + category);
            check(LiquidBounceActionLock.blocksModule(new Child(new Child(module))) == expected, "child " + category);
            check(LiquidBounceActionLock.blocksScheduledTask(new Task(module)) == expected, "queued " + category);
            check(LiquidBounceActionLock.blocksIgnoredEvent(new Hook(module), new PacketEvent()) == expected, "ignored " + category);
            check(!LiquidBounceActionLock.blocksIgnoredEvent(new Hook(module), new GameTickEvent()), "tick cleanup " + category);
        }
        ActionLockClient.clear();
        check(!LiquidBounceActionLock.blocksModule(new Module(Category.COMBAT)), "wake unlock");
        var lease = new PolicyHeartbeatLease(); lease.receive(1);
        check(!lease.expired(1_000_000_001L, false), "fresh");
        check(!lease.expired(2_000_000_001L, true), "pause entry");
        check(!lease.expired(120_000_000_001L, true), "long pause");
        check(!lease.expired(121_000_000_001L, false), "resume");
        check(lease.expired(124_000_000_001L, false), "silent lease expiry");
        System.out.println("PASS sleep policy: nine categories, parent choices, queued actions, ignored-packet guards, tick cleanup, wake and pause-aware expiry");
    }
}
