package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.config.types.Value", remap = false)
public abstract class CapabilityValueMixin {
    @ModifyVariable(method = {"set(Ljava/lang/Object;)V", "set(Ljava/lang/Object;Lkotlin/jvm/functions/Function1;)V",
            "setInner$liquidbounce(Ljava/lang/Object;)V"}, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Object erbridge$boundedWrite(Object value) {
        return LiquidBounceCapabilityAccess.normalizeValue(this, value, true);
    }

    @Inject(method = "setInner$liquidbounce(Ljava/lang/Object;)V", at = @At("HEAD"), cancellable = true)
    private void erbridge$normalEnabledLifecycle(Object requested, CallbackInfo ci) {
        if (LiquidBounceCapabilityAccess.routeInternalEnabled(this, requested)) ci.cancel();
    }

    @Inject(method = "get()Ljava/lang/Object;", at = @At("RETURN"), cancellable = true)
    private void erbridge$boundedRead(CallbackInfoReturnable<Object> result) {
        result.setReturnValue(LiquidBounceCapabilityAccess.normalizeValue(this, result.getReturnValue(), false));
    }
}
