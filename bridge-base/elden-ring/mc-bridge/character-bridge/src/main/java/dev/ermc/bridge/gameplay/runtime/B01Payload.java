package dev.ermc.bridge.gameplay.runtime;

import dev.ermc.bridge.api.CapabilitySnapshot;
import dev.ermc.bridge.api.CapabilitySnapshot.*;
import dev.ermc.bridge.api.CharacterBinding;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Stable ability IDs and bounded settings; v1 peers cannot consume this versioned channel. */
public record B01Payload(UUID owner, boolean challenge, CapabilitySnapshot snapshot,
                         int level, int earned, int spent, int remaining, String status) implements CustomPacketPayload {
    public static final Type<B01Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("erbridge", "b01_policy_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, B01Payload> CODEC = StreamCodec.of(B01Payload::write, B01Payload::read);
    public B01Payload {
        if (owner == null || status == null || status.length() > 256 || level < 0 || level > 713
                || earned < 0 || earned > 100000 || spent < 0 || spent > 100000 || remaining < 0 || remaining > 100000
                || snapshot != null && !snapshot.binding().playerId().equals(owner)) throw new IllegalArgumentException("Invalid B01 payload");
        if (snapshot != null) validate(snapshot);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private static void validate(CapabilitySnapshot snapshot) {
        AbilityCatalog catalog = AbilityCatalog.instance();
        if (!snapshot.abilities().keySet().equals(catalog.entries().stream().map(AbilityCatalog.Entry::id).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Capability catalog mismatch");
        for (var item : snapshot.abilities().entrySet()) {
            var entry = catalog.entry(item.getKey()); Grant grant = item.getValue();
            if (grant.rank() > entry.ranks().size()) throw new IllegalArgumentException("Unsupported rank");
            if (!grant.unlocked()) continue;
            var rank = entry.ranks().get(grant.rank() - 1);
            if (!rank.modes().equals(grant.allowedModes()) || !rank.limits().keySet().equals(grant.numericLimits().keySet()))
                throw new IllegalArgumentException("Capability schema mismatch");
            grant.numericLimits().forEach((name, limit) -> {
                var maximum = rank.limits().get(name);
                if (limit.minimum() < maximum.minimum() || limit.maximum() > maximum.maximum())
                    throw new IllegalArgumentException("Capability exceeds shipped safe bounds");
            });
        }
    }
    private static void write(RegistryFriendlyByteBuf b, B01Payload p) {
        b.writeVarInt(2); b.writeUUID(p.owner); b.writeBoolean(p.challenge); b.writeBoolean(p.snapshot != null);
        if (p.snapshot != null) {
            b.writeUUID(p.snapshot.binding().campaignId()); b.writeUUID(p.snapshot.binding().characterId()); b.writeLong(p.snapshot.policyRevision());
            b.writeVarInt(p.snapshot.abilities().size());
            for (var entry : new TreeMap<>(p.snapshot.abilities()).entrySet()) {
                b.writeUtf(entry.getKey().id(), 48); Grant g = entry.getValue();
                b.writeVarInt(g.rank()); b.writeBoolean(g.visible()); b.writeVarInt(g.allowedModes().size());
                for (String mode : new TreeSet<>(g.allowedModes())) b.writeUtf(mode, 64);
                b.writeVarInt(g.numericLimits().size());
                for (var limit : new TreeMap<>(g.numericLimits()).entrySet()) {
                    b.writeUtf(limit.getKey(), 128); b.writeDouble(limit.getValue().minimum()); b.writeDouble(limit.getValue().maximum());
                }
            }
        }
        b.writeVarInt(p.level); b.writeVarInt(p.earned); b.writeVarInt(p.spent); b.writeVarInt(p.remaining); b.writeUtf(p.status, 256);
    }
    private static int size(RegistryFriendlyByteBuf b, int max) {
        int value = b.readVarInt(); if (value < 0 || value > max) throw new IllegalArgumentException("Oversized policy field"); return value;
    }
    private static B01Payload read(RegistryFriendlyByteBuf b) {
        if (b.readVarInt() != 2) throw new IllegalArgumentException("Unsupported policy wire version");
        UUID owner = b.readUUID(); boolean challenge = b.readBoolean(); CapabilitySnapshot snapshot = null;
        if (b.readBoolean()) {
            CharacterBinding binding = new CharacterBinding(owner, b.readUUID(), b.readUUID()); long revision = b.readLong();
            Map<Ability, Grant> grants = new LinkedHashMap<>(); int count = size(b, 64);
            for (int i = 0; i < count; i++) {
                Ability ability = new Ability(b.readUtf(48)); int rank = size(b, 8); boolean visible = b.readBoolean();
                int modesCount = size(b, 16); Set<String> modes = new HashSet<>();
                for (int j = 0; j < modesCount; j++) if (!modes.add(b.readUtf(64))) throw new IllegalArgumentException("Duplicate mode");
                int limitsCount = size(b, 64); Map<String, NumericRange> limits = new HashMap<>();
                for (int j = 0; j < limitsCount; j++) if (limits.put(b.readUtf(128), new NumericRange(b.readDouble(), b.readDouble())) != null)
                    throw new IllegalArgumentException("Duplicate setting");
                if (grants.put(ability, new Grant(rank, visible, modes, limits)) != null) throw new IllegalArgumentException("Duplicate ability");
            }
            snapshot = new CapabilitySnapshot(binding, revision, grants);
        }
        return new B01Payload(owner, challenge, snapshot, b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(256));
    }
}
