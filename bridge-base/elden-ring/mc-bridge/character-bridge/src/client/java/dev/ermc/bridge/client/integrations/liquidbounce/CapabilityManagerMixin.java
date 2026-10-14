package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.features.module.ModuleManager", remap = false)
public abstract class CapabilityManagerMixin {
    @Inject(method = "addModule(Lnet/ccbluex/liquidbounce/features/module/ClientModule;)V", at = @At("TAIL"))
    private void erbridge$registerCompleteModule(@Coerce Object module, CallbackInfo ci) {
        LiquidBounceCapabilityAccess.register(module);
    }
}
