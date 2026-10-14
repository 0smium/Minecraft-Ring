package dev.ermc.bridge.character.equipment;

import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ErLink;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

/** A local owner's vanilla menu; client requests carry no native addresses or inventory claims. */
public final class HostEquipmentMenus {
    public static final MenuType<HostEquipmentMenu> TYPE = Registry.register(BuiltInRegistries.MENU,
        ResourceLocation.fromNamespaceAndPath("er_character_bridge", "host_equipment"),
        new MenuType<>(HostEquipmentMenu::new, FeatureFlags.DEFAULT_FLAGS));
    private HostEquipmentMenus() {}
    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(Open.TYPE, Open.CODEC);
        PayloadTypeRegistry.playC2S().register(Intent.TYPE, Intent.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Intent.TYPE, (intent, context) -> {
            var player = context.player();
            if (player.containerMenu instanceof HostEquipmentMenu menu && menu.containerId == intent.containerId()) menu.acceptIntent(player, intent);
        });
        ServerPlayNetworking.registerGlobalReceiver(Open.TYPE, (payload, context) -> {
            var player = context.player();
            if (!LocalHostOwner.isOwner(player) || !player.isAlive() || !TerrainManager.isBridgeWorld()
                    || !ErLink.get().alive() || player.containerMenu != player.inventoryMenu
                    || !player.containerMenu.getCarried().isEmpty()) return;
            player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new HostEquipmentMenu(id, inventory, player),
                Component.literal("法环装备")));
        });
    }
    /** Only the observed identity is carried. The server owns the native snapshot and request token. */
    public record Intent(int containerId, int epoch, int armorIndex, boolean remove, int inventorySlot, int handle, int itemId) implements CustomPacketPayload {
        public static final Type<Intent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("er_character_bridge", "equipment_intent"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Intent> CODEC = StreamCodec.of(
            (b,p) -> { b.writeInt(p.containerId); b.writeInt(p.epoch); b.writeInt(p.armorIndex); b.writeBoolean(p.remove); b.writeInt(p.inventorySlot); b.writeInt(p.handle); b.writeInt(p.itemId); },
            b -> new Intent(b.readInt(), b.readInt(), b.readInt(), b.readBoolean(), b.readInt(), b.readInt(), b.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Open() implements CustomPacketPayload {
        public static final Open INSTANCE = new Open();
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("er_character_bridge", "equipment_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
