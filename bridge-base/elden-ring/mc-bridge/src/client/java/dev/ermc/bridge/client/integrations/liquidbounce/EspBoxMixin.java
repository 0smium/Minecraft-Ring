package dev.ermc.bridge.client.integrations.liquidbounce;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Uses the host proxy's rectangular AABB instead of the square vanilla dimensions box. */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.features.module.modules.render.ModuleESP$BoxMode", remap = false)
public abstract class EspBoxMixin {
	// Build #3530's inlined map has one LivingEntity local at this inflate invocation.
	@ModifyExpressionValue(
		method = "renderHandler$lambda$6(Lnet/ccbluex/liquidbounce/event/events/WorldRenderEvent;)Lkotlin/Unit;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/class_238;method_1014(D)Lnet/minecraft/class_238;", remap = false),
		remap = false
	)
	private static AABB erbridge$hostBox(AABB original, @Local LivingEntity entity) {
		if (!(entity instanceof ErEntity proxy)) return original;
		return proxy.getBoundingBox().move(-proxy.getX(), -proxy.getY(), -proxy.getZ()).inflate(0.05);
	}
}
