package dev.ermc.bridge.client.integrations.meteor;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ermc.bridge.client.integrations.HostEntityView;
import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Meteor's layout while replacing only the generic proxy's name, display HP and color inputs. */
@Pseudo
@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.render.Nametags", remap = false)
public abstract class NametagsMixin {
    @ModifyExpressionValue(method = "renderGenericLivingNametag(Lnet/minecraft/class_1309;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1299;method_5897()Lnet/minecraft/class_2561;", remap = false),
            remap = false)
    private Component erbridge$name(Component original, @Local(argsOnly = true) LivingEntity entity) {
        return entity instanceof ErEntity ? entity.getName() : original;
    }

    @ModifyExpressionValue(method = "renderGenericLivingNametag(Lnet/minecraft/class_1309;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_6032()F", remap = false), remap = false)
    private float erbridge$health(float original, @Local(argsOnly = true) LivingEntity entity) {
        return entity instanceof ErEntity proxy ? (HostEntityView.available(proxy) ? proxy.hp() : 0) : original;
    }

    @ModifyExpressionValue(method = "renderGenericLivingNametag(Lnet/minecraft/class_1309;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_6063()F", remap = false), remap = false)
    private float erbridge$maximum(float original, @Local(argsOnly = true) LivingEntity entity) {
        return entity instanceof ErEntity proxy ? (HostEntityView.available(proxy) ? proxy.maxHp() : 1) : original;
    }

    @ModifyExpressionValue(method = "renderGenericLivingNametag(Lnet/minecraft/class_1309;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1309;method_6067()F", remap = false), remap = false)
    private float erbridge$absorption(float original, @Local(argsOnly = true) LivingEntity entity) {
        return entity instanceof ErEntity ? 0 : original;
    }

    @ModifyExpressionValue(method = "renderGenericLivingNametag(Lnet/minecraft/class_1309;Z)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;valueOf(I)Ljava/lang/String;"), remap = false)
    private String erbridge$healthText(String original, @Local(argsOnly = true) LivingEntity entity) {
        return entity instanceof ErEntity proxy ? HostEntityView.healthText(proxy) : original;
    }
}
