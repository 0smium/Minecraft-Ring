package dev.ermc.bridge.gameplay.runtime;

import dev.ermc.bridge.api.CapabilitySnapshot;
import dev.ermc.bridge.api.CapabilitySnapshot.*;
import dev.ermc.bridge.api.CharacterBinding;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Owner-only server -> client policy. No client-supplied levels, points or ranks are accepted. */
public record B01Payload(UUID owner, boolean challenge, CapabilitySnapshot snapshot,
                         int level, int earned, int spent, int remaining, String status) implements CustomPacketPayload {
    public static final Type<B01Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("erbridge", "b01_policy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, B01Payload> CODEC = StreamCodec.of(B01Payload::write, B01Payload::read);

    public B01Payload {
        if (owner == null || status == null || status.length() > 256 || level < 0 || level > 713
                || earned < 0 || earned > 100000 || spent < 0 || spent > 100000 || remaining < 0 || remaining > 100000
                || snapshot != null && !snapshot.binding().playerId().equals(owner)) throw new IllegalArgumentException("Invalid B01 payload");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void write(RegistryFriendlyByteBuf b, B01Payload p) {
        b.writeUUID(p.owner); b.writeBoolean(p.challenge); b.writeBoolean(p.snapshot != null);
        if (p.snapshot != null) {
            b.writeUUID(p.snapshot.binding().campaignId()); b.writeUUID(p.snapshot.binding().characterId()); b.writeLong(p.snapshot.policyRevision());
            for (Ability ability : Ability.values()) {
                Grant g = p.snapshot.abilities().get(ability);
                b.writeVarInt(g.rank()); b.writeBoolean(g.visible());
                if (g.rank() > 0) {
                    NumericRange range = g.numericLimits().get(ability == Ability.SPEED ? "speed" : "range");
                    b.writeDouble(range.minimum()); b.writeDouble(range.maximum());
                }
            }
        }
        b.writeVarInt(p.level); b.writeVarInt(p.earned); b.writeVarInt(p.spent); b.writeVarInt(p.remaining); b.writeUtf(p.status, 256);
    }
    private static B01Payload read(RegistryFriendlyByteBuf b) {
        UUID owner = b.readUUID(); boolean challenge = b.readBoolean(); CapabilitySnapshot snapshot = null;
        if (b.readBoolean()) {
            CharacterBinding binding = new CharacterBinding(owner, b.readUUID(), b.readUUID()); long revision = b.readLong();
            EnumMap<Ability, Grant> grants = new EnumMap<>(Ability.class);
            for (Ability ability : Ability.values()) {
                int rank = b.readVarInt(); boolean visible = b.readBoolean();
                if (rank < 0 || rank > 3) throw new IllegalArgumentException("Unsupported B01 rank");
                if (rank == 0) grants.put(ability, new Grant(0, visible, Set.of(), Map.of()));
                else {
                    double minimum = b.readDouble(), maximum = b.readDouble();
                    if (minimum < (ability == Ability.SPEED ? .05 : 1) || maximum > (ability == Ability.SPEED ? .6 : 6))
                        throw new IllegalArgumentException("Unsupported B01 range");
                    grants.put(ability, new Grant(rank, visible, Set.of(ability == Ability.SPEED ? "ground_custom" : "single_target"),
                            Map.of(ability == Ability.SPEED ? "speed" : "range", new NumericRange(minimum, maximum))));
                }
            }
            snapshot = new CapabilitySnapshot(binding, revision, grants);
        }
        return new B01Payload(owner, challenge, snapshot, b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(256));
    }
}
