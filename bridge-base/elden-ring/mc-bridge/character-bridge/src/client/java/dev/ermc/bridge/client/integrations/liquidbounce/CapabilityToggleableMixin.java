package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.config.types.ToggleableConfigurable", remap = false)
public abstract class CapabilityToggleableMixin {
    @Inject(method = "getRunning()Z", at = @At("HEAD"), cancellable = true)
    private void erbridge$groundedStrafeOnly(CallbackInfoReturnable<Boolean> result) {
        if (LiquidBounceCapabilityAccess.skipToggleable(this)) result.setReturnValue(false);
    }
}
