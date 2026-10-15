package dev.ermc.bridge.client.integrations.liquidbounce;

import com.google.gson.JsonElement;
import dev.ermc.bridge.client.integrations.LiquidBounceGuiPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.client.ModuleRequest", remap = false)
public abstract class CapabilitySettingsViewMixin {
    @ModifyArg(method = "acceptGetSettingsRequest", at = @At(value = "INVOKE", target = "Lnet/ccbluex/netty/http/util/HttpResponseKt;httpOk(Lcom/google/gson/JsonElement;)Lio/netty/handler/codec/http/FullHttpResponse;"), index = 0)
    private JsonElement erbridge$learnedSettings(JsonElement settings) { return LiquidBounceGuiPolicy.settings(settings); }
}
