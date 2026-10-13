package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura", remap = false)
public abstract class CapabilityAuraMixin {
    @Inject(method = "<clinit>()V", at = @At("TAIL"))
    private static void erbridge$registerSingleAura(CallbackInfo ci) {
        LiquidBounceCapabilityAccess.register("net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura");
    }

    @Inject(method = "checkIfReadyToAttack(Lnet/minecraft/class_1297;)Z", at = @At("HEAD"), cancellable = true)
    private void erbridge$boundEachAttack(Entity target, CallbackInfoReturnable<Boolean> result) {
        if (!LiquidBounceCapabilityAccess.allowAuraAttack(target)) result.setReturnValue(false);
    }
}
