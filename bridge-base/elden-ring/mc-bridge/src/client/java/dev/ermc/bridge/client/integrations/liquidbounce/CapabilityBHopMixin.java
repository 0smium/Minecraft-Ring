package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes.SpeedBHopBase", remap = false)
public abstract class CapabilityBHopMixin {
    @Inject(method = "handleMovementInput$lambda$0(Lnet/ccbluex/liquidbounce/features/module/modules/movement/speed/modes/SpeedBHopBase;Lnet/ccbluex/liquidbounce/event/events/MovementInputEvent;)Lkotlin/Unit;",
            at = @At("HEAD"), cancellable = true)
    private static void erbridge$keepManualJump(CallbackInfoReturnable<Object> result) {
        if (LiquidBounceCapabilityAccess.challenge()) result.setReturnValue(LiquidBounceCapabilityAccess.kotlinUnit());
    }
}
