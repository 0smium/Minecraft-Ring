package dev.ermc.bridge.client.action.mixin;

import dev.ermc.bridge.client.action.ActionLockClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class SleepInputMixin {
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void erbridge$sleepBeforeInput(CallbackInfo ci) {
        if (ActionLockClient.blocksActions()) ActionLockClient.stopActiveInput(Minecraft.getInstance());
    }

    // Runs after vanilla and LB input modifiers, without cancelling the physics tick.
    @Inject(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void erbridge$sleepAfterInput(CallbackInfo ci) {
        if (ActionLockClient.blocksActions()) ActionLockClient.clearInput(((LocalPlayer)(Object)this).input);
    }

    // Vanilla auto-jump can set Input.jumping after Input.tick; clear again before travel.
    @Inject(method = "serverAiStep", at = @At("TAIL"))
    private void erbridge$sleepBeforeTravel(CallbackInfo ci) {
        if (ActionLockClient.blocksActions()) ActionLockClient.stopActiveInput(Minecraft.getInstance());
    }
}
