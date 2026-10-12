package dev.ermc.bridge.client.integrations.liquidbounce;

import dev.ermc.bridge.client.integrations.MinimapScanAccess;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The pinned HUD subscribes its minimap even when the component is disabled. */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.integration.theme.component.types.minimap.ChunkRenderer$MinimapChunkUpdateSubscriber", remap = false)
public abstract class MinimapScanMixin {
	@Inject(method = "<clinit>()V", at = @At("TAIL"))
	private static void erbridge$watchMinimap(CallbackInfo ci) {
		ClientTickEvents.END_CLIENT_TICK.register(MinimapScanAccess::tick);
	}

	@Inject(method = {"chunkUpdate(II)V", "recordBlock(Lnet/minecraft/class_2338;Lnet/minecraft/class_2680;Z)V"},
		at = @At("HEAD"), cancellable = true)
	private void erbridge$skipDisabledBridgeScan(CallbackInfo ci) {
		if (MinimapScanAccess.suppressed()) ci.cancel();
	}
}
