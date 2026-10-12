package dev.ermc.bridge.client.mixin;

import dev.ermc.bridge.client.MovingPlatformClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnose the vanilla move between the bridge's carry and the final feet position. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMovementMixin {
    @Unique private boolean erbridge$tracePlatformMove;

    @Inject(method = "move", at = @At("HEAD"))
    private void erbridge$beforeMove(MoverType type, Vec3 movement, CallbackInfo callback) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        erbridge$tracePlatformMove = MovingPlatformClient.tracingPhysics(player);
        if (erbridge$tracePlatformMove) MovingPlatformClient.tracePhysics(player, type, movement, "BEFORE");
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void erbridge$afterMove(MoverType type, Vec3 movement, CallbackInfo callback) {
        if (erbridge$tracePlatformMove) {
            MovingPlatformClient.tracePhysics((LocalPlayer)(Object)this, type, movement, "AFTER");
            erbridge$tracePlatformMove = false;
        }
    }
}
