package dev.ermc.bridge;

/** Unknown/occluded samples are never permission to add terrain or carve existing collision. */
public final class TerrainRayEvidence {
    private TerrainRayEvidence() {}
    public static boolean known(int[] flags, int first, int count) {
        if (first < 0 || count < 0 || first > flags.length - count) return false;
        for (int i = first; i < first + count; i++) if (flags[i] != 0 && flags[i] != 1) return false;
        return true;
    }
}
