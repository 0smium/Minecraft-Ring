package dev.ermc.bridge.mixin;

import dev.ermc.bridge.api.PlayerFlight;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom flight owns velocity, while vanilla retains collision and gravity outside its lease. */
@Mixin(Player.class)
public abstract class PrecisionFlightPlayerMixin {
    @ModifyExpressionValue(method = "travel", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z"))
    private boolean erbridge$customFlightTravel(boolean original) {
        Player player = (Player) (Object) this;
        // Only this algorithm branch changes. Creative's stored ability and packet state stay intact.
        return original && !PlayerFlight.customActive(player);
    }

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void erbridge$customFlightLanding(float distance, float multiplier, DamageSource source,
            CallbackInfoReturnable<Boolean> result) {
        Player player = (Player) (Object) this;
        if (PlayerFlight.customActive(player)) { player.fallDistance = 0; result.setReturnValue(false); }
    }
}
