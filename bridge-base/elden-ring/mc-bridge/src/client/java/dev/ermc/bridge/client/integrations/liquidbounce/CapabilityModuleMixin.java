package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.features.module.ClientModule", remap = false)
public abstract class CapabilityModuleMixin {
    @Inject(method = "enabled_delegate$lambda$7(Lnet/ccbluex/liquidbounce/features/module/ClientModule;Ljava/lang/String;Z)Z",
            at = @At("HEAD"), cancellable = true)
    private static void erbridge$authorizeBeforeActivation(@Coerce Object module, String name, boolean enabled,
            CallbackInfoReturnable<Boolean> result) {
        if (enabled && !LiquidBounceCapabilityAccess.allowActivation(module)) result.setReturnValue(false);
    }

    @Inject(method = "getRunning()Z", at = @At("HEAD"), cancellable = true)
    private void erbridge$executionGuard(CallbackInfoReturnable<Boolean> result) {
        if (!LiquidBounceCapabilityAccess.allowModule(this)) result.setReturnValue(false);
    }
}
