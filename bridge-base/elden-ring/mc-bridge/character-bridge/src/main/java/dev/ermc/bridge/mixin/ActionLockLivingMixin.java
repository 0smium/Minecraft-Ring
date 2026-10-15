package dev.ermc.bridge.mixin;

import dev.ermc.bridge.api.PlayerActions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remove requested acceleration, not external velocity or the vanilla physics tick. */
@Mixin(LivingEntity.class)
public abstract class ActionLockLivingMixin {
    private boolean erbridge$actionsBlocked() {
        return (Object) this instanceof Player player && !PlayerActions.allowed(player);
    }
    @ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
    private Vec3 erbridge$suspendMovementInput(Vec3 input) { return erbridge$actionsBlocked() ? Vec3.ZERO : input; }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void erbridge$suspendJump(CallbackInfo ci) { if (erbridge$actionsBlocked()) ci.cancel(); }

    @Inject(method = "startUsingItem", at = @At("HEAD"), cancellable = true)
    private void erbridge$suspendUse(InteractionHand hand, CallbackInfo ci) { if (erbridge$actionsBlocked()) ci.cancel(); }
}
