package dev.ermc.bridge.mixin;

import dev.ermc.bridge.api.PlayerActions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Also rejects direct Player.attack calls rather than trusting only normal input callbacks. */
@Mixin(Player.class)
public abstract class ActionLockPlayerMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void erbridge$suspendAttack(Entity target, CallbackInfo ci) {
        if (!PlayerActions.allowed((Player) (Object) this)) ci.cancel();
    }
}
