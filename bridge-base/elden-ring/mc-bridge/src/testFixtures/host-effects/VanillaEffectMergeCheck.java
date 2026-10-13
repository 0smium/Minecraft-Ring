package dev.ermc.bridge.effects;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Exercises real cached Minecraft effect instances, without starting either game or a server. */
public final class VanillaEffectMergeCheck {
    private static Field hidden;
    private static Method tickDown;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        hidden = MobEffectInstance.class.getDeclaredField("hiddenEffect");
        hidden.setAccessible(true);
        tickDown = MobEffectInstance.class.getDeclaredMethod("tickDownDuration");
        tickDown.setAccessible(true);

        MobEffectInstance top = slow(100, 3, false, true, true);
        top.update(slow(600, 0, true, false, false));
        check(top.getDuration() == 100 && top.getAmplifier() == 3);
        MobEffectInstance external = hidden(top);
        check(external.getDuration() == 600 && external.getAmplifier() == 0);
        check(external.isAmbient() && !external.isVisible() && !external.showIcon());
        for (int i = 0; i < 90; i++) {
            tickDown.invoke(top);
            if (top.getDuration() <= 25) top.update(slow(top.getDuration() + 75, 3, false, true, true));
        }
        check(hidden(top) == external && external.getDuration() == 510);

        top = slow(100, 3, false, true, true);
        top.update(slow(-1, 0, true, false, false));
        for (int i = 0; i < 10; i++) tickDown.invoke(top);
        check(hidden(top).isInfiniteDuration());

        top = slow(100, 3, false, true, true);
        top.update(slow(2000, 0, true, false, false));
        top.update(slow(600, 1, false, true, false));
        for (int i = 0; i < 10; i++) tickDown.invoke(top);
        external = hidden(top);
        check(external.getAmplifier() == 1 && external.getDuration() == 590);
        check(hidden(external).getAmplifier() == 0 && hidden(external).getDuration() == 1990);
        check(hidden(external).isAmbient() && !hidden(external).isVisible() && !hidden(external).showIcon());
        // A vanilla copy constructor deliberately drops the suppressed chain: restoration must use the instance.
        check(hidden(new MobEffectInstance(external)) == null);
        System.out.println("PASS: actual MC 1.21.1 weaker/longer merge, hidden ticking, renewal, infinite/nested chains and presentation flags");
        System.out.println("LIMIT: no Fabric injection, player/server lifecycle or game startup in this probe");
    }

    private static MobEffectInstance slow(int ticks, int amplifier, boolean ambient, boolean particles, boolean icon) {
        return new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, amplifier, ambient, particles, icon);
    }

    private static MobEffectInstance hidden(MobEffectInstance effect) throws Exception {
        return (MobEffectInstance) hidden.get(effect);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
