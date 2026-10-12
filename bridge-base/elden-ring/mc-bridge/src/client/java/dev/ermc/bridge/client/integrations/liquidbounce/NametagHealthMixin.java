package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

/** Host HP is display data; Minecraft health and combat calculations remain untouched. */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.features.module.modules.render.nametags.NametagTextFormatter", remap = false)
public abstract class NametagHealthMixin {
	@Shadow(remap = false) @Final private Entity entity;

	@Inject(method = "getHealthText()Lnet/minecraft/class_2561;", at = @At("HEAD"), cancellable = true, remap = false)
	private void erbridge$hostHealth(CallbackInfoReturnable<Component> cir) {
		if (!(entity instanceof ErEntity proxy)) return;
		float hp = proxy.hp(), maxHp = proxy.maxHp();
		if (!Float.isFinite(hp) || !Float.isFinite(maxHp) || hp < 0 || maxHp <= 0) {
			cir.setReturnValue(Component.literal("ER HP ?").withStyle(ChatFormatting.GRAY));
			return;
		}
		float fraction = Math.min(1.0F, hp / maxHp);
		ChatFormatting color = fraction >= 0.7F ? ChatFormatting.GREEN
			: fraction >= 0.4F ? ChatFormatting.YELLOW : ChatFormatting.RED;
		cir.setReturnValue(Component.literal(String.format(Locale.ROOT, "%.0f / %.0f ER HP", hp, maxHp)).withStyle(color));
	}
}
