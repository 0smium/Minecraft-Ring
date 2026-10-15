package dev.ermc.bridge.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World-owned generation receipts survive harvesting, reconnects and terrain resampling. */
public final class SurfaceResourceData extends SavedData {
    private static final String NAME = "erbridge_surface_resources";
    private static final Factory<SurfaceResourceData> FACTORY = new Factory<>(SurfaceResourceData::new,
        (tag, registries) -> load(tag), null);
    private final LongOpenHashSet generated = new LongOpenHashSet();
    private boolean enabled = true;
    private boolean compatible = true;
    private final List<SurfaceResourceExclusion> exclusions = new ArrayList<>();
    private int nextExclusionId = 1;

    public static SurfaceResourceData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }
    public boolean enabled() { return enabled && compatible; }
    public boolean compatible() { return compatible; }
    public int count() { return generated.size(); }
    public boolean contains(long id) { return generated.contains(id); }
    public void generated(long id) { if (compatible && generated.add(id)) setDirty(); }
    public void enabled(boolean value) { if (compatible && enabled != value) { enabled = value; setDirty(); } }
    public List<SurfaceResourceExclusion> exclusions() { return List.copyOf(exclusions); }
    public long exclusionCount(SurfaceResourceExclusion.Scope scope) {
        return exclusions.stream().filter(rule -> rule.scope().equals(scope)).count();
    }
    public boolean excluded(SurfaceResourceExclusion.Scope scope, SurfaceResourcePlan.Candidate candidate) {
        if (!compatible) return true;
        int radius = SurfaceResourcePlan.radius(candidate.kind());
        return exclusions.stream().anyMatch(rule -> rule.overlaps(scope, candidate.x(), candidate.z(), radius));
    }
    public SurfaceResourceExclusion exclude(SurfaceResourceExclusion.Scope scope, boolean wholeZone,
                                            int x, int z, int radius, String name) {
        if (!compatible) throw new IllegalStateException("资源记录损坏或版本不兼容，不能更改禁区。");
        if (exclusions.size() >= SurfaceResourceExclusion.MAX_RULES) {
            throw new IllegalArgumentException("禁区数量达到上限（128），请先按编号移除不需要的规则。");
        }
        if (nextExclusionId == Integer.MAX_VALUE) throw new IllegalArgumentException("禁区编号已耗尽，请保留记录供排查。");
        var rule = new SurfaceResourceExclusion(nextExclusionId, scope, wholeZone, x, z, radius, name);
        exclusions.add(rule); nextExclusionId++; setDirty(); return rule;
    }
    public boolean removeExclusion(int id) {
        if (!compatible) return false;
        boolean removed = exclusions.removeIf(rule -> rule.id() == id);
        if (removed) setDirty();
        return removed;
    }

    public static SurfaceResourceData load(CompoundTag tag) {
        SurfaceResourceData data = new SurfaceResourceData();
        int version = tag.getInt("version");
        data.compatible = tag.contains("version", Tag.TAG_INT) && (version == 1 || version == 2)
            && tag.contains("enabled", Tag.TAG_BYTE) && (tag.getByte("enabled") == 0 || tag.getByte("enabled") == 1)
            && tag.contains("generated", Tag.TAG_LONG_ARRAY);
        data.enabled = tag.contains("enabled") && tag.getBoolean("enabled");
        for (long id : tag.getLongArray("generated")) data.generated.add(id);
        if (data.compatible && version == 2) {
            try {
                require(tag, "nextExclusionId", Tag.TAG_INT);
                require(tag, "exclusions", Tag.TAG_LIST);
                ListTag rules = (ListTag) tag.get("exclusions");
                if (rules.size() > SurfaceResourceExclusion.MAX_RULES
                        || (!rules.isEmpty() && rules.getElementType() != Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("Invalid exclusion list");
                }
                data.nextExclusionId = tag.getInt("nextExclusionId");
                if (data.nextExclusionId < 1) throw new IllegalArgumentException("Invalid exclusion sequence");
                var ids = new HashSet<Integer>();
                for (int i = 0; i < rules.size(); i++) {
                    var rule = readExclusion(rules.getCompound(i));
                    if (!ids.add(rule.id()) || rule.id() >= data.nextExclusionId) {
                        throw new IllegalArgumentException("Duplicate or invalid exclusion id");
                    }
                    data.exclusions.add(rule);
                }
            } catch (IllegalArgumentException invalid) {
                // Preserve all receipts and refuse both generation and save; never reset to an empty ledger.
                data.compatible = false;
            }
        }
        return data;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Invalid resource field: " + key);
    }

    private static SurfaceResourceExclusion readExclusion(CompoundTag tag) {
        for (String key : new String[]{"id", "zone", "region", "x", "z", "radius"}) require(tag, key, Tag.TAG_INT);
        for (String key : new String[]{"ax", "ay", "az", "unitsPerMeter"}) require(tag, key, Tag.TAG_DOUBLE);
        for (String key : new String[]{"flipZ", "wholeZone"}) {
            require(tag, key, Tag.TAG_BYTE);
            if (tag.getByte(key) != 0 && tag.getByte(key) != 1) throw new IllegalArgumentException("Invalid flag");
        }
        require(tag, "name", Tag.TAG_STRING);
        var scope = new SurfaceResourceExclusion.Scope(tag.getInt("zone"), tag.getInt("region"),
            tag.getDouble("ax"), tag.getDouble("ay"), tag.getDouble("az"), tag.getDouble("unitsPerMeter"),
            tag.getBoolean("flipZ"));
        return new SurfaceResourceExclusion(tag.getInt("id"), scope, tag.getBoolean("wholeZone"),
            tag.getInt("x"), tag.getInt("z"), tag.getInt("radius"), tag.getString("name"));
    }

    private static CompoundTag writeExclusion(SurfaceResourceExclusion rule) {
        CompoundTag tag = new CompoundTag();
        var scope = rule.scope();
        tag.putInt("id", rule.id()); tag.putString("name", rule.name());
        tag.putInt("zone", scope.zone()); tag.putInt("region", scope.region());
        tag.putDouble("ax", scope.ax()); tag.putDouble("ay", scope.ay()); tag.putDouble("az", scope.az());
        tag.putDouble("unitsPerMeter", scope.unitsPerMeter()); tag.putBoolean("flipZ", scope.flipZ());
        tag.putBoolean("wholeZone", rule.wholeZone());
        tag.putInt("x", rule.x()); tag.putInt("z", rule.z()); tag.putInt("radius", rule.radius());
        return tag;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // An unknown format cannot be silently overwritten by toggling this generator.
        if (!compatible) throw new IllegalStateException("Unsupported surface resource data version");
        tag.putInt("version", 2);
        tag.putBoolean("enabled", enabled);
        tag.putLongArray("generated", generated.toLongArray());
        tag.putInt("nextExclusionId", nextExclusionId);
        ListTag rules = new ListTag();
        for (var rule : exclusions) rules.add(writeExclusion(rule));
        tag.put("exclusions", rules);
        return tag;
    }
}
