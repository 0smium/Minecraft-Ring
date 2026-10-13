package dev.ermc.bridge.client.integrations.meteor;

import dev.ermc.bridge.client.integrations.HostEntityView;
import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Host HP fraction is projected onto 20 MC HP for mixed target lists; no proxy health is written. */
@Pseudo
@Mixin(targets = "meteordevelopment.meteorclient.utils.entity.SortPriority", remap = false)
public abstract class HealthSortMixin {
    @Redirect(method = "sortHealth(Lnet/minecraft/class_1297;Lnet/minecraft/class_1297;)I",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_6032()F", remap = false), remap = false)
    private static float erbridge$comparableHealth(LivingEntity entity) {
        if (!(entity instanceof ErEntity proxy)) return entity.getHealth();
        float fraction = HostEntityView.fraction(proxy);
        return Float.isFinite(fraction) ? fraction * 20.0F : Float.POSITIVE_INFINITY;
    }
}
