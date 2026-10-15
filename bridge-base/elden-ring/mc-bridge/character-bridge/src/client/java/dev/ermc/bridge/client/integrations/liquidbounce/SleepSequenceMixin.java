package dev.ermc.bridge.client.integrations.liquidbounce;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ermc.bridge.client.action.LiquidBounceActionLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Preserve upstream cancellation of delayed actions, including owners that override getRunning. */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.event.SequenceManager", remap = false)
public abstract class SleepSequenceMixin {
    @WrapOperation(method = "tickSequences$lambda$0", at = @At(value = "INVOKE",
            target = "Lnet/ccbluex/liquidbounce/event/EventListener;getRunning()Z"))
    private static boolean erbridge$sleepSequence(@Coerce Object owner, Operation<Boolean> original) {
        return !LiquidBounceActionLock.blocksModule(owner) && original.call(owner);
    }
}
