package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.action.LiquidBounceActionLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** LB's EventScheduler executes queued actions without checking running; discard them at execution. */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.utils.client.EventScheduler", remap = false)
public abstract class SleepScheduledActionMixin {
    @Inject(method = "process$lambda$4(Lnet/ccbluex/liquidbounce/event/Event;Lnet/ccbluex/liquidbounce/utils/client/EventScheduler$ScheduleInfo;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void erbridge$sleepScheduledAction(@Coerce Object event, @Coerce Object task,
            CallbackInfoReturnable<Boolean> cir) {
        if (LiquidBounceActionLock.blocksScheduledTask(task)) cir.setReturnValue(true);
    }
}
