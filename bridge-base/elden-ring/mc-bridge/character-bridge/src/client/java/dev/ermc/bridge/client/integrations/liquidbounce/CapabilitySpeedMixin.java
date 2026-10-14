package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.features.module.modules.movement.speed.ModuleSpeed", remap = false)
public abstract class CapabilitySpeedMixin {
    @Inject(method = "<clinit>()V", at = @At("TAIL"))
    private static void erbridge$registerGroundSpeed(CallbackInfo ci) {
        LiquidBounceCapabilityAccess.register("net.ccbluex.liquidbounce.features.module.modules.movement.speed.ModuleSpeed");
    }
}
