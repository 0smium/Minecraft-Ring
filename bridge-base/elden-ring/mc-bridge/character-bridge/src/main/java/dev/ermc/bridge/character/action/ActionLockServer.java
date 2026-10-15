package dev.ermc.bridge.character.action;

import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.api.PlayerActions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import org.slf4j.LoggerFactory;

/** Consumes HostEffectsBridge's already validated observation; never infers sleep from potion icons. */
public final class ActionLockServer {
    private ActionLockServer() {}
    private static long epochs = Math.max(1, System.nanoTime() & Long.MAX_VALUE);
    private static MinecraftServer server;
    private static ServerPlayer holder;
    private static long epoch, sequence;
    private static int lastSentTick;
    private static boolean sleeping;

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(ActionLockPayload.TYPE, ActionLockPayload.CODEC);
        PlayerActions.ALLOW.register(player -> !(player instanceof ServerPlayer nativeOwner) || !blocks(nativeOwner));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, face) ->
            PlayerActions.allowed(player) ? InteractionResult.PASS : InteractionResult.FAIL);
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) ->
            PlayerActions.allowed(player) ? InteractionResult.PASS : InteractionResult.FAIL);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
            PlayerActions.allowed(player) ? InteractionResult.PASS : InteractionResult.FAIL);
        UseEntityCallback.EVENT.register((player, level, hand, target, hit) ->
            PlayerActions.allowed(player) ? InteractionResult.PASS : InteractionResult.FAIL);
        UseItemCallback.EVENT.register((player, level, hand) -> PlayerActions.allowed(player)
            ? InteractionResultHolder.pass(player.getItemInHand(hand)) : InteractionResultHolder.fail(player.getItemInHand(hand)));
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> PlayerActions.allowed(player));
        BridgeLifecycle.PLAYER_DISCONNECTED.register(player -> { if (holder == player) clear(); });
        BridgeLifecycle.BEFORE_RECALL.register(player -> { if (holder == player) clear(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(active -> { if (server == active) clear(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(active -> { if (server == active) reset(); });
    }

    public static boolean blocks(ServerPlayer player) {
        return sleeping && holder == player && player.isAlive() && !player.isRemoved()
            && player.getServer() == server && LocalHostOwner.isOwner(player);
    }

    /** Called every valid effect tick, including the explicit awake state. */
    public static void observe(ServerPlayer player, boolean asleep) {
        if (player == null || !player.isAlive() || player.isRemoved() || !LocalHostOwner.isOwner(player)) {
            clear(); return;
        }
        if (server != player.getServer()) {
            reset(); server = player.getServer(); epoch = ++epochs;
        }
        boolean changedOwner = holder != player;
        if (changedOwner) { clear(); holder = player; }
        boolean changed = changedOwner || sleeping != asleep;
        if (sleeping != asleep) LoggerFactory.getLogger("erbridge").info("[actions] sleep {} for {}",
            asleep ? "entered" : "released", player.getName().getString());
        sleeping = asleep;
        if (sleeping) {
            // Cancel an in-progress use without releasing a charged bow or crossbow.
            if (player.isUsingItem()) player.stopUsingItem();
            player.setSprinting(false);
            // Allow vanilla gravity. Do not zero velocity: damage, knockback and platforms still act.
            if (player.getAbilities().flying) { player.getAbilities().flying = false; player.onUpdateAbilities(); }
        }
        if (changed || player.tickCount - lastSentTick >= 10 || player.tickCount < lastSentTick) publish();
    }

    public static void clear() {
        if (holder != null && sleeping) { sleeping = false; publish(); }
        holder = null; sleeping = false;
    }

    private static void publish() {
        if (holder == null || epoch <= 0) return;
        lastSentTick = holder.tickCount;
        if (ServerPlayNetworking.canSend(holder, ActionLockPayload.TYPE)) {
            ServerPlayNetworking.send(holder, new ActionLockPayload(holder.getUUID(), epoch, ++sequence, sleeping));
        }
    }

    public static void reset() {
        clear(); server = null; epoch = sequence = 0; lastSentTick = 0;
    }
}
