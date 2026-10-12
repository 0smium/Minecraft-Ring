package dev.ermc.bridge.mixin;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.PlatformCollisionTrace;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.LoggerFactory;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayerMovementMixin {
    @Shadow public ServerPlayer player;
    @Shadow private double lastGoodX;
    @Shadow private double lastGoodY;
    @Shadow private double lastGoodZ;

    @Unique private ServerboundMovePlayerPacket erbridge$movePacket;
    @Unique private Vec3 erbridge$moveStart;
    @Unique private Vec3 erbridge$moveLastGood;

    // PacketUtils has already transferred this call to the server thread, and
    // vanilla has rejected invalid movement, passengers and pending teleports.
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void erbridge$prepareLiftCollision(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        TerrainManager.beforePlayerMove(player);
        erbridge$movePacket = null;
        try {
            if (TerrainManager.platformTraceActive(player)) {
                erbridge$moveStart = player.position();
                erbridge$moveLastGood = new Vec3(lastGoodX, lastGoodY, lastGoodZ);
                erbridge$movePacket = packet;
            }
        } catch (RuntimeException failure) {
            // Optional observation must not interfere with vanilla movement.
            LoggerFactory.getLogger("erbridge").info("[platform-trace] server collision trace failed tick {}: {}",
                player.tickCount, failure.getClass().getSimpleName());
        }
    }

    @Inject(method = "isPlayerCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/phys/AABB;DDD)Z",
        at = @At("RETURN"))
    private void erbridge$traceRejectedLiftMove(LevelReader level, AABB oldBox, double x, double y, double z,
            CallbackInfoReturnable<Boolean> callback) {
        try {
            if (callback.getReturnValue() && erbridge$movePacket != null && TerrainManager.platformTraceActive(player))
                PlatformCollisionTrace.rejected(player, level, oldBox, erbridge$moveStart, erbridge$moveLastGood,
                    new Vec3(x, y, z), erbridge$movePacket.isOnGround());
        } catch (RuntimeException failure) {
            // Keep the original return value even if the diagnostic read fails.
            LoggerFactory.getLogger("erbridge").info("[platform-trace] server collision trace failed tick {}: {}",
                player.tickCount, failure.getClass().getSimpleName());
        } finally {
            erbridge$movePacket = null;
        }
    }
}
