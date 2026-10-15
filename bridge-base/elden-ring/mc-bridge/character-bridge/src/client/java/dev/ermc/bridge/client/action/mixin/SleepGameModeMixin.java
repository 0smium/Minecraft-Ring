package dev.ermc.bridge.client.action.mixin;

import dev.ermc.bridge.client.action.ActionLockClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Final client interaction boundary, including calls made without vanilla key handling. */
@Mixin(MultiPlayerGameMode.class)
public abstract class SleepGameModeMixin {
    @Inject(method = {"destroyBlock", "startDestroyBlock", "continueDestroyBlock"},
            at = @At("HEAD"), cancellable = true)
    private void erbridge$sleepDestroy(CallbackInfoReturnable<Boolean> cir) {
        if (ActionLockClient.blocksActions()) cir.setReturnValue(false);
    }

    @Inject(method = {"useItem", "useItemOn", "interact", "interactAt"},
            at = @At("HEAD"), cancellable = true)
    private void erbridge$sleepInteract(CallbackInfoReturnable<InteractionResult> cir) {
        if (ActionLockClient.blocksActions()) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void erbridge$sleepAttack(CallbackInfo ci) {
        if (ActionLockClient.blocksActions()) ci.cancel();
    }
}
