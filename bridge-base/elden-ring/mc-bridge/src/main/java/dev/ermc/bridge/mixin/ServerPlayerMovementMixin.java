package dev.ermc.bridge.mixin;

import dev.ermc.bridge.TerrainManager;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayerMovementMixin {
    @Shadow public ServerPlayer player;

    // PacketUtils has already transferred this call to the server thread, and
    // vanilla has rejected invalid movement, passengers and pending teleports.
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void erbridge$prepareLiftCollision(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        TerrainManager.beforePlayerMove(player);
    }
}
