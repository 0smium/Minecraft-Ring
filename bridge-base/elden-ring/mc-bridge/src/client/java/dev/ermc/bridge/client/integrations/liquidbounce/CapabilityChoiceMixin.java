package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.config.types.ChoiceConfigurable", remap = false)
public abstract class CapabilityChoiceMixin {
    @Inject(method = "getActiveChoice()Lnet/ccbluex/liquidbounce/config/types/Choice;", at = @At("RETURN"), cancellable = true)
    private void erbridge$effectiveChoice(CallbackInfoReturnable<Object> result) {
        result.setReturnValue(LiquidBounceCapabilityAccess.normalizeChoice(this, result.getReturnValue()));
    }

    @Inject(method = "setActiveChoice(Lnet/ccbluex/liquidbounce/config/types/Choice;)V", at = @At("HEAD"), cancellable = true)
    private void erbridge$rejectUnsupportedChoice(@Coerce Object selected, CallbackInfo ci) {
        if (LiquidBounceCapabilityAccess.normalizeChoice(this, selected) != selected) ci.cancel();
    }
}
