package dev.ermc.bridge.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Keeps vanilla's suppressed external effects when the bridge releases its own top effect. */
@Mixin(MobEffectInstance.class)
public interface MobEffectInstanceAccessor {
    @Accessor("hiddenEffect")
    MobEffectInstance erbridge$getHiddenEffect();
}
