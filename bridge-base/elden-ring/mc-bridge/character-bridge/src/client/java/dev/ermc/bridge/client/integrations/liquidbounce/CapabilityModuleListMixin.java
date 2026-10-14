package dev.ermc.bridge.client.integrations.liquidbounce;

import com.google.gson.JsonElement;
import dev.ermc.bridge.client.integrations.LiquidBounceGuiPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.client.ModuleFunctionsKt", remap = false)
public abstract class CapabilityModuleListMixin {
    @ModifyArg(method = "getModules", at = @At(value = "INVOKE", target = "Lnet/ccbluex/netty/http/util/HttpResponseKt;httpOk(Lcom/google/gson/JsonElement;)Lio/netty/handler/codec/http/FullHttpResponse;"), index = 0)
    private static JsonElement erbridge$visibleModules(JsonElement modules) { return LiquidBounceGuiPolicy.modules(modules); }
}
