package dev.ermc.bridge.gameplay.runtime;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client declares activity; only the server acknowledgement authorizes custom flight. */
public record PrecisionFlightPayload(UUID owner, long sequence, long hostStart, int hostLife, boolean active) implements CustomPacketPayload {
    public static final Type<PrecisionFlightPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("erbridge", "precision_flight_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PrecisionFlightPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> { buffer.writeUUID(payload.owner); buffer.writeLong(payload.sequence); buffer.writeLong(payload.hostStart); buffer.writeInt(payload.hostLife); buffer.writeBoolean(payload.active); },
        buffer -> new PrecisionFlightPayload(buffer.readUUID(), buffer.readLong(), buffer.readLong(), buffer.readInt(), buffer.readBoolean()));
    public PrecisionFlightPayload {
        if (owner == null || sequence <= 0) throw new IllegalArgumentException("Invalid flight activity");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
