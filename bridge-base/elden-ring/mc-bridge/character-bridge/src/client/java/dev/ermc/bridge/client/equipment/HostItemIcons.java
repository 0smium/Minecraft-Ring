package dev.ermc.bridge.client.equipment;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ermc.bridge.character.equipment.HostItemAssets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/** Bounded lazy texture cache. The PNGs stay local and are never bundled in the mod. */
public final class HostItemIcons {
    private static final Map<Integer, ResourceLocation> textures = new LinkedHashMap<>(16, .75f, true);
    private static final Set<Integer> missing = new HashSet<>();
    private HostItemIcons() {}

    public static boolean render(GuiGraphics graphics, ItemStack stack, int x, int y) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        var tag = data.copyTag();
        if (!tag.contains("erbridge_item_id", 3)) return false;
        return render(graphics, tag.getInt("erbridge_item_id"), x, y, 16);
    }

    public static boolean render(GuiGraphics graphics, int rawItemId, int x, int y, int size) {
        var item = HostItemAssets.item(rawItemId);
        if (item.isEmpty() || item.get().icon() == 0) return false;
        int icon = item.get().icon();
        ResourceLocation texture = textures.get(icon);
        if (texture == null && !missing.contains(icon)) {
            var file = HostItemAssets.root().resolve("icons").resolve(icon + ".png");
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > 512 * 1024) { missing.add(icon); return false; }
                NativeImage image;
                try (var input = Files.newInputStream(file)) { image = NativeImage.read(input); }
                if (image.getWidth() != 64 || image.getHeight() != 64) { image.close(); missing.add(icon); return false; }
                texture = ResourceLocation.fromNamespaceAndPath("er_character_bridge", "host_icon/" + icon);
                Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
                textures.put(icon, texture);
                if (textures.size() > 256) {
                    var eldest = textures.entrySet().iterator().next();
                    Minecraft.getInstance().getTextureManager().release(eldest.getValue());
                    textures.remove(eldest.getKey());
                }
            } catch (Exception error) { missing.add(icon); return false; }
        }
        if (texture == null) return false;
        // This page projects a vanilla fallback stack. Cover its icon before drawing the local original.
        graphics.fill(x, y, x + size, y + size, 0xFF8B8B8B);
        graphics.blit(texture, x, y, size, size, 0, 0, 64, 64, 64, 64);
        return true;
    }

    public static void reload() {
        for (var texture : textures.values()) Minecraft.getInstance().getTextureManager().release(texture);
        textures.clear(); missing.clear(); HostItemAssets.reload();
    }
}
