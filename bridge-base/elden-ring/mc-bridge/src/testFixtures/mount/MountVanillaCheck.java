package dev.ermc.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Actual Minecraft stacks and wire codec; no fake Horse, world, entity tick or game launch. */
public final class MountVanillaCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        List<ItemStack> full = new ArrayList<>();
        for (int i = 0; i < 36; i++) full.add(new ItemStack(Items.STONE, 64));
        ItemStack saddle = new ItemStack(Items.SADDLE);
        ItemStack armour = new ItemStack(Items.IRON_HORSE_ARMOR);
        check(!MountRuntime.fits(full, List.of(saddle)), "full inventory must retain the real horse");
        full.set(4, ItemStack.EMPTY);
        check(MountRuntime.fits(full, List.of(saddle)), "borrowed saddle fits its vacated slot");
        check(!MountRuntime.fits(full, List.of(saddle, armour)), "new armour needs its own free slot");
        check(full.get(4).isEmpty() && saddle.getCount() == 1, "capacity check may not move or clone equipment");
        full.set(5, ItemStack.EMPTY);
        check(MountRuntime.fits(full, List.of(saddle, armour)), "both actual nonstackable equipment items fit");
        full.set(5, new ItemStack(Items.STONE, 63));
        check(MountRuntime.fits(full, List.of(new ItemStack(Items.STONE, 65))), "split merge and empty slot");
        check(!MountRuntime.fits(full, List.of(new ItemStack(Items.STONE, 66))), "one-item overflow rejected");
        ItemStack renamed = new ItemStack(Items.STONE);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("owner item"));
        full.set(4, new ItemStack(Items.STONE, 63));
        check(!MountRuntime.fits(full, List.of(renamed)), "different components must not merge");
        check(MountRuntime.fits(full, List.of(ItemStack.EMPTY)), "empty equipment needs no slot");

        UUID owner = UUID.randomUUID(), horse = UUID.randomUUID();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var mount = new MountPayload(owner, horse);
            MountPayload.CODEC.encode(buffer, mount);
            check(MountPayload.CODEC.decode(buffer).equals(mount), "S2C identity round trip");
            MountPayload.CODEC.encode(buffer, new MountPayload(owner, null));
            check(MountPayload.CODEC.decode(buffer).horse() == null, "S2C clear round trip");
            MountPayload.Release.CODEC.encode(buffer, MountPayload.Release.INSTANCE);
            check(MountPayload.Release.CODEC.decode(buffer).equals(MountPayload.Release.INSTANCE), "release carries no client-authoritative identity");
        } finally { buffer.release(); }
        System.out.println("PASS: real MC 1.21.1 stack/component capacity boundaries and mount payload round trips");
        System.out.println("LIMIT: no Horse world tick, riding controls, live dismount or game startup in this check");
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
