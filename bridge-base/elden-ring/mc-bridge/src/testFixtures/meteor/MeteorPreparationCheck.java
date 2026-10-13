package dev.ermc.bridge.meteor;

import dev.ermc.bridge.client.integrations.HostEntityView;
import dev.ermc.bridge.client.integrations.meteor.*;
import dev.ermc.bridge.client.integrations.liquidbounce.TargetConfigurableMixin;
import dev.ermc.bridge.entity.ErEntity;
import dev.ermc.bridge.link.ErLink;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Objects;

/** Tests handler semantics with stubs; not a substitute for applying Mixins to the real client. */
public final class MeteorPreparationCheck {
    public static void main(String[] args) throws Exception {
        for (String version : new String[] {null, "0.5.7", "0.5.8", "0.5.8-dev", "0.5.9"}) {
            FabricLoader.version = version;
            var plugin = new MeteorMixinPlugin();
            plugin.onLoad("fixture");
            equal("0.5.8".equals(version), plugin.shouldApplyMixin("fixture", "fixture"));
        }
        var proxy = new ErEntity();
        equal(true, HostEntityView.targetable(proxy));
        equal("1000 / 2000 ER HP", HostEntityView.healthText(proxy));
        equal(0.5F, HostEntityView.fraction(proxy));
        var aura = new KillAuraMixin() {};
        var result = new CallbackInfoReturnable<Boolean>();
        call(aura, "erbridge$rejectUnavailableHost", proxy, result);
        equal(false, result.cancelled); // valid proxy still goes through Meteor's own restrictions
        proxy.enemy = false;
        call(aura, "erbridge$rejectUnavailableHost", proxy, result);
        equal(false, result.value); equal(true, result.cancelled);
        proxy.enemy = true;
        result = new CallbackInfoReturnable<>();
        call(aura, "erbridge$rejectUnavailableHost", new Entity(), result);
        equal(false, result.cancelled);
        ErLink.get().connected = false;
        equal(false, HostEntityView.targetable(proxy));
        equal("ER HP ?", HostEntityView.healthText(proxy));
        ErLink.get().connected = true;
        proxy.health = Float.NaN;
        equal(false, HostEntityView.targetable(proxy));
        proxy.health = 1000;
        var nametags = new NametagsMixin() {};
        equal(1000F, call(nametags, "erbridge$health", 20F, proxy));
        equal(2000F, call(nametags, "erbridge$maximum", 20F, proxy));
        equal(0F, call(nametags, "erbridge$absorption", 4F, proxy));
        equal("1000 / 2000 ER HP", call(nametags, "erbridge$healthText", "20", proxy));
        equal("host name", ((Component) call(nametags, "erbridge$name", new Component("type"), proxy)).getString());
        var normal = new LivingEntity();
        equal(8F, call(nametags, "erbridge$health", 8F, normal));
        equal(10F, call(HealthSortMixin.class, "erbridge$comparableHealth", proxy));
        equal(20F, call(HealthSortMixin.class, "erbridge$comparableHealth", normal));
        equal(20F, proxy.getHealth()); // display/sorting never writes proxy combat health
        var lb = new TargetConfigurableMixin() {
            @Override public boolean getHostile() { return true; }
            @Override public boolean getInvisible() { return false; }
        };
        var lbResult = new CallbackInfoReturnable<Boolean>();
        call(lb, "erbridge$hostileProxy", proxy, lbResult);
        equal(true, lbResult.value);
        proxy.invisible = true;
        call(lb, "erbridge$hostileProxy", proxy, lbResult);
        equal(false, lbResult.value);
        System.out.println("PASS: optional version gate, hostile veto, normal entity preservation, names/HP/sort and LB shared semantics");
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass().getSuperclass();
        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method.invoke(target instanceof Class<?> ? null : target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
