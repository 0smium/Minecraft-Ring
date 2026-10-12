package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.entity.ErEntity;
import dev.ermc.bridge.link.ErLink;
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
		boolean liveEnemy = TerrainManager.isBridgeWorld() && ErLink.get().alive()
			&& proxy.hostId() != 0L && proxy.isTargetableEnemy() && proxy.isAlive()
			&& Float.isFinite(proxy.hp()) && proxy.hp() > 0.0F
			&& Float.isFinite(proxy.maxHp()) && proxy.maxHp() > 0.0F;
		cir.setReturnValue(liveEnemy && getHostile() && (getInvisible() || !proxy.isInvisible()));
	}
}
