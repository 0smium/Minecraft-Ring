package dev.ermc.bridge.character.action;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A short, server-authored restriction; it grants no ability and contains no native address. */
public record ActionLockPayload(UUID owner, long epoch, long sequence, boolean sleeping) implements CustomPacketPayload {
    public static final Type<ActionLockPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
        "er_character_bridge", "action_lock_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ActionLockPayload> CODEC = StreamCodec.of(
        (buffer, value) -> {
            buffer.writeUUID(value.owner()); buffer.writeLong(value.epoch());
            buffer.writeLong(value.sequence()); buffer.writeBoolean(value.sleeping());
        }, buffer -> new ActionLockPayload(buffer.readUUID(), buffer.readLong(), buffer.readLong(), buffer.readBoolean()));
    public ActionLockPayload {
        if (owner == null || epoch <= 0 || sequence <= 0) throw new IllegalArgumentException("Invalid action restriction");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
