package dev.ermc.bridge.effects;

import dev.ermc.bridge.HostEffectsBridge;
import dev.ermc.bridge.link.*;
import dev.ermc.bridge.link.HostEffectRules.Effect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Standalone platform-stub fixture, intentionally outside Gradle's normal test source set. */
public final class HostEffectsCheck {
    private static final int BASE = Protocol.OFF_HOST_EFFECTS;
    private static final int READY = Protocol.HOST_EFFECT_VALID | Protocol.HOST_EFFECT_MC_CONTROL;

    public static void main(String[] args) throws Exception {
        codec(Path.of(args[0]));
        cursor();
        rules();
        bridge();
        System.out.println("PASS: Java codec/cursor/rules/real bridge with platform stubs; frostBurst="
                + Boolean.getBoolean("erbridge.experimentalFrostBurst"));
    }

    private static void codec(Path nativeWire) throws Exception {
        byte[] bytes = Files.readAllBytes(nativeWire);
        equal(Protocol.HOST_EFFECTS_SIZE, bytes.length);
        ByteBuffer buf = ByteBuffer.allocate(BASE + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(BASE); buf.put(bytes);
        var state = new HostEffectState();
        check(HostEffectCodec.read(buf, state));
        equal(2, state.version); equal(127, state.flags); equal(100L, state.frame);
        equal(7, state.hostLife); equal(4001, state.poisonEffectId); equal(4100, state.rotEffectId);
        equal(5000, state.frostEffectId); equal(6000, state.sleepEffectId); equal(7000, state.madnessEffectId);
        equal(1, state.frostProcs);
        buf.putInt(BASE + Protocol.HFX_SEQ, 5);
        check(!HostEffectCodec.read(buf, state));
        buf.putInt(BASE + Protocol.HFX_SEQ, 6);
        buf.putInt(BASE + Protocol.HFX_VERSION, 3);
        check(!HostEffectCodec.read(buf, state));
        buf.putInt(BASE + Protocol.HFX_VERSION, 2);
        buf.putInt(BASE + Protocol.HFX_ROT_ID, -1);
        check(!HostEffectCodec.read(buf, state));
        buf.putInt(BASE + Protocol.HFX_ROT_ID, 4100);
        buf.limit(BASE + 0x20);
        check(!HostEffectCodec.read(buf, state));
        buf.limit(buf.capacity());
        buf.putInt(BASE + Protocol.HFX_VERSION, 1);
        check(HostEffectCodec.read(buf, state));
        equal(READY | Protocol.HOST_EFFECT_POISON, state.flags);
        equal(-1, state.rotEffectId); equal(-1, state.frostEffectId); equal(0, state.frostProcs);
        buf.putInt(BASE + Protocol.HFX_MAGIC, 0);
        check(!HostEffectCodec.read(buf, state));
        equal(1, state.version); // failed reads do not mutate the caller's last complete state
        System.out.println("PASS: C++ -> Java v2 bytes, v1 fallback, torn/unknown/truncated/inconsistent snapshots");
    }

    private static void cursor() {
        var cursor = new HostEffectCursor();
        var s = state(READY, 1, 0);
        check(!cursor.observe(s));
        s.flags |= Protocol.HOST_EFFECT_FROST; s.frame++; s.frostProcs++;
        check(cursor.observe(s)); check(!cursor.observe(s));
        s.frame++; check(!cursor.observe(s));
        s.frame = 1; s.frostProcs = 0; check(!cursor.observe(s));
        s.frame = 4; s.frostProcs = 1; check(!cursor.observe(s)); // stale sample did not rewind counter
        s.flags = Protocol.HOST_EFFECT_VALID; s.frame++; check(!cursor.observe(s));
        s.flags = READY | Protocol.HOST_EFFECT_FROST; s.frame++; check(!cursor.observe(s));
        s.hostLife++; s.frame++; s.frostProcs++; check(!cursor.observe(s));
        s.frame++; s.frostProcs += 2; check(!cursor.observe(s)); // no accumulated damage replay
        cursor.reset(); s.frostProcs = -1; s.frame++; check(!cursor.observe(s));
        s.frostProcs = 0; s.frame++; check(cursor.observe(s)); // uint32 wrap
        s.version = 1; s.frame++; check(!cursor.observe(s));
        System.out.println("PASS: burst dedup, stale/F8/life/reconnect baselines and counter wrap");
    }

    private static void rules() {
        var s = state(127, 1, 0);
        var all = HostEffectRules.desired(s);
        equal(6, all.size());
        equal(3, all.get(Effect.SLOWNESS).amplifier());
        equal(0, all.get(Effect.WITHER).amplifier());
        equal(0, all.get(Effect.WITHER).extension() % 40);
        equal(0, all.get(Effect.POISON).extension() % 25);
        s.flags &= ~Protocol.HOST_EFFECT_SLEEP;
        equal(0, HostEffectRules.desired(s).get(Effect.SLOWNESS).amplifier());
        check(!HostEffectRules.desired(s).containsKey(Effect.BLINDNESS));
        s.flags &= ~Protocol.HOST_EFFECT_VALID;
        check(HostEffectRules.desired(s).isEmpty());
    }

    private static void bridge() throws Exception {
        HostEffectsBridge.reset();
        var server = new MinecraftServer();
        var owner = new ServerPlayer(server.world);
        var guest = new ServerPlayer(server.world);
        server.players.all.put(owner.id, owner); server.players.all.put(guest.id, guest);
        server.profile = new MinecraftServer.Profile(owner.id);
        var link = ErLink.get();
        link.state.version = 2; link.state.hostLife = link.life = 7;
        link.state.flags = READY | Protocol.HOST_EFFECT_POISON | Protocol.HOST_EFFECT_ROT;
        link.state.frame = link.render = 1;
        HostEffectsBridge.onServerTick(server);
        check(owner.hasEffect(MobEffects.POISON) && owner.hasEffect(MobEffects.WITHER));
        check(!guest.hasEffect(MobEffects.POISON) && !guest.hasEffect(MobEffects.WITHER));
        for (int i = 0; i < 800; i++) {
            owner.tick(); link.state.frame++; link.render++;
            HostEffectsBridge.onServerTick(server);
        }
        equal(32, owner.poisonTicks); equal(20, owner.witherTicks);
        link.state.flags = READY | Protocol.HOST_EFFECT_FROST | Protocol.HOST_EFFECT_SLEEP | Protocol.HOST_EFFECT_MADNESS;
        advance(server);
        check(!owner.hasEffect(MobEffects.POISON) && !owner.hasEffect(MobEffects.WITHER));
        check(owner.hasEffect(MobEffects.BLINDNESS) && owner.hasEffect(MobEffects.CONFUSION) && owner.hasEffect(MobEffects.WEAKNESS));
        equal(3, owner.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier());
        link.state.flags &= ~Protocol.HOST_EFFECT_SLEEP;
        advance(server);
        equal(0, owner.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier());
        check(!owner.hasEffect(MobEffects.BLINDNESS));
        link.state.frostProcs++;
        advance(server);
        int bursts = Boolean.getBoolean("erbridge.experimentalFrostBurst") ? 1 : 0;
        equal(bursts, owner.bursts);
        HostEffectsBridge.onServerTick(server); advance(server);
        equal(bursts, owner.bursts);
        link.connected = false; HostEffectsBridge.onServerTick(server);
        check(!owner.hasEffect(MobEffects.MOVEMENT_SLOWDOWN));
        link.connected = true; advance(server);
        equal(bursts, owner.bursts);
        owner.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 500, 2));
        advance(server); // external stronger/extended effect is no longer owned
        link.state.flags = READY; advance(server);
        equal(2, owner.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier());
        owner.addEffect(new MobEffectInstance(MobEffects.POISON, 500, 0));
        link.state.flags = READY | Protocol.HOST_EFFECT_POISON; advance(server);
        link.state.flags = READY; advance(server);
        check(owner.hasEffect(MobEffects.POISON)); // pre-existing potion survives host cure
        owner.removeEffect(MobEffects.POISON);
        link.state.flags |= Protocol.HOST_EFFECT_ROT; advance(server);
        var age = HostEffectsBridge.class.getDeclaredField("effectAdvancedAt");
        age.setAccessible(true); age.setLong(null, System.nanoTime() / 1_000_000L - 2000);
        HostEffectsBridge.onServerTick(server);
        check(!owner.hasEffect(MobEffects.WITHER));
        advance(server);
        check(owner.hasEffect(MobEffects.WITHER));
        server.profile = null; advance(server);
        check(!owner.hasEffect(MobEffects.WITHER) && !guest.hasEffect(MobEffects.WITHER));
        server.profile = new MinecraftServer.Profile(owner.id); advance(server);
        HostEffectsBridge.onServerStopping(server);
        check(!owner.hasEffect(MobEffects.WITHER));
        check(owner.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)); // never removes external slow
        HostEffectsBridge.reset();
        System.out.println("PASS: host owner only, lease cadence, stacked ailments, outside effects, stale/stop cleanup");
    }

    private static void advance(MinecraftServer server) {
        ErLink.get().state.frame++; ErLink.get().render++;
        HostEffectsBridge.onServerTick(server);
    }

    private static HostEffectState state(int flags, long frame, int counter) {
        var s = new HostEffectState();
        s.version = 2; s.flags = flags; s.frame = frame; s.frostProcs = counter; s.hostLife = 7;
        return s;
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
