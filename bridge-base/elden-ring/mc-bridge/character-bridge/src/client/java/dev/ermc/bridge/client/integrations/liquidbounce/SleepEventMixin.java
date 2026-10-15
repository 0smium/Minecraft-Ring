package dev.ermc.bridge.client.integrations.liquidbounce;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ermc.bridge.client.action.LiquidBounceActionLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.event.EventManager", remap = false)
public abstract class SleepEventMixin {
    @WrapOperation(method = "callEvent", at = @At(value = "INVOKE",
            target = "Lnet/ccbluex/liquidbounce/event/EventHook;getIgnoreNotRunning()Z"))
    private boolean erbridge$sleepIgnoredEvents(@Coerce Object hook, Operation<Boolean> original, @Coerce Object event) {
        return original.call(hook) && !LiquidBounceActionLock.blocksIgnoredEvent(hook, event);
    }

    @WrapOperation(method = "callEvent", at = @At(value = "INVOKE",
            target = "Lnet/ccbluex/liquidbounce/event/EventListener;getRunning()Z"))
    private boolean erbridge$sleepOverriddenRunning(@Coerce Object owner, Operation<Boolean> original) {
        return !LiquidBounceActionLock.blocksModule(owner) && original.call(owner);
    }
}
