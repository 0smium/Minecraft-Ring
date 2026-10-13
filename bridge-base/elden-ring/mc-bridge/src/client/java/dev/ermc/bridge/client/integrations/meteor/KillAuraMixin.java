package dev.ermc.bridge.client.integrations.meteor;

import dev.ermc.bridge.client.integrations.HostEntityView;
import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a veto, not an unconditional acceptance: Meteor still checks its type list/range/visibility. */
@Pseudo
@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.combat.KillAura", remap = false)
public abstract class KillAuraMixin {
    @Inject(method = "entityCheck(Lnet/minecraft/class_1297;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void erbridge$rejectUnavailableHost(Entity entity, CallbackInfoReturnable<Boolean> result) {
        if (entity instanceof ErEntity proxy && !HostEntityView.targetable(proxy)) result.setReturnValue(false);
    }
}
