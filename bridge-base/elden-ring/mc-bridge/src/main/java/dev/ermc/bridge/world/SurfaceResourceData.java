package dev.ermc.bridge.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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

    public static SurfaceResourceData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }
    public boolean enabled() { return enabled && compatible; }
    public boolean compatible() { return compatible; }
    public int count() { return generated.size(); }
    public boolean contains(long id) { return generated.contains(id); }
    public void generated(long id) { if (generated.add(id)) setDirty(); }
    public void enabled(boolean value) { if (enabled != value) { enabled = value; setDirty(); } }

    public static SurfaceResourceData load(CompoundTag tag) {
        SurfaceResourceData data = new SurfaceResourceData();
        data.compatible = tag.contains("version", Tag.TAG_INT) && tag.getInt("version") == 1
            && tag.contains("enabled", Tag.TAG_BYTE) && tag.contains("generated", Tag.TAG_LONG_ARRAY);
        data.enabled = tag.contains("enabled") && tag.getBoolean("enabled");
        for (long id : tag.getLongArray("generated")) data.generated.add(id);
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // An unknown format cannot be silently overwritten by toggling this generator.
        if (!compatible) throw new IllegalStateException("Unsupported surface resource data version");
        tag.putInt("version", 1);
        tag.putBoolean("enabled", enabled);
        tag.putLongArray("generated", generated.toLongArray());
        return tag;
    }
}
