package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.HostEntityView;
import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds the bridge's live hostile proxies to LB's entity categories, after its tags/friends checks. */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.utils.combat.TargetConfigurable", remap = false)
public abstract class TargetConfigurableMixin {
	@Shadow(remap = false)
	public abstract boolean getHostile();

	@Shadow(remap = false)
	public abstract boolean getInvisible();

	@Inject(method = "isInteresting", at = @At("HEAD"), cancellable = true, remap = false)
	private void erbridge$hostileProxy(Entity suspect, CallbackInfoReturnable<Boolean> cir) {
		if (!(suspect instanceof ErEntity proxy)) return;
		boolean liveEnemy = HostEntityView.targetable(proxy);
		cir.setReturnValue(liveEnemy && getHostile() && (getInvisible() || !proxy.isInvisible()));
	}
}
