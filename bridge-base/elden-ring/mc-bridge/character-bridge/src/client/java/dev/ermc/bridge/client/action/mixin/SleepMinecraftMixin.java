package dev.ermc.bridge.client.action.mixin;

import dev.ermc.bridge.client.action.ActionLockClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class SleepMinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void erbridge$sleepAttack(CallbackInfoReturnable<Boolean> cir) {
        if (ActionLockClient.blocksActions()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void erbridge$sleepMining(boolean attack, CallbackInfo ci) {
        if (ActionLockClient.blocksActions()) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void erbridge$sleepUse(CallbackInfo ci) {
        if (ActionLockClient.blocksActions()) ci.cancel();
    }
}
