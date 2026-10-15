package dev.ermc.bridge.client.equipment.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Match the vanilla recipe overlay decision without duplicating its width threshold. */
@Mixin(InventoryScreen.class)
public interface InventoryScreenAccessor {
    @Accessor("widthTooNarrow") boolean erbridge$widthTooNarrow();
}
