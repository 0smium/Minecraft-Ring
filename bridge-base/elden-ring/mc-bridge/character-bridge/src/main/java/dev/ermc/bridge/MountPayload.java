package dev.ermc.bridge;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-authorized identity only. The client never supplies a mount or rider identity. */
public record MountPayload(UUID owner, UUID horse) implements CustomPacketPayload {
    public static final Type<MountPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("erbridge", "mount"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MountPayload> CODEC = StreamCodec.of(
        (b, p) -> { b.writeUUID(p.owner); b.writeBoolean(p.horse != null); if (p.horse != null) b.writeUUID(p.horse); },
        b -> new MountPayload(b.readUUID(), b.readBoolean() ? b.readUUID() : null));
    public MountPayload { java.util.Objects.requireNonNull(owner); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** A client may only relinquish its own ride when handing control back with F8. */
    public record Release() implements CustomPacketPayload {
        public static final Release INSTANCE = new Release();
        public static final Type<Release> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("erbridge", "mount_release"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Release> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
