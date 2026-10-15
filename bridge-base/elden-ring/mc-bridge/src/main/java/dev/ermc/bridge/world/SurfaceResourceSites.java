package dev.ermc.bridge.world;

import java.util.LinkedHashMap;
import java.util.Map;

/** Only native ray observations qualify a surface; saved proxy blocks are not evidence. */
public final class SurfaceResourceSites {
    private static final int LIMIT = 8192;
    private static final long MAX_AGE_MS = 60_000;
    private record Ground(double y, long first, long last, int observations) {}
    private final Map<Long, Ground> ground = new LinkedHashMap<>();

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    public void clear() { ground.clear(); }
    public int size() { return ground.size(); }
    public void forget(int x, int z) { ground.remove(key(x, z)); }

    public void observe(int x, int z, double y, boolean eligible, long now) {
        long key = key(x, z);
        if (!eligible || !Double.isFinite(y)) { ground.remove(key); return; }
        Ground old = ground.remove(key);
        boolean same = old != null && now >= old.last && now - old.last <= MAX_AGE_MS
            && Math.abs(old.y - y) <= .0625;
        ground.put(key, new Ground(y, same ? old.first : now, now,
            same ? Math.min(2, old.observations + (now - old.first >= 200 ? 1 : 0)) : 1));
        if (ground.size() > LIMIT) ground.remove(ground.keySet().iterator().next());
    }

    /** Null means wait or skip; no permanent rejection for incomplete sampling. */
    public Integer baseY(SurfaceResourcePlan.Candidate candidate, long now) {
        Ground center = ground.get(key(candidate.x(), candidate.z()));
        if (!fresh(center, now) || center.observations < 2 || now - center.first < 500) return null;
        int base = (int) Math.floor(center.y - .0001);
        int radius = SurfaceResourcePlan.radius(candidate.kind());
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            Ground sample = ground.get(key(candidate.x() + dx, candidate.z() + dz));
            // The little soil/stone base may raise the sampled surface by at most .35 blocks.
            // Slopes, a low ceiling, unknown columns and tier changes wait for another site.
            if (!fresh(sample, now) || sample.y < base + .65 || sample.y > base + 1.0001
                    || Math.abs(sample.y - center.y) > .25) return null;
        }
        return base;
    }

    private static boolean fresh(Ground sample, long now) {
        return sample != null && now >= sample.last && now - sample.last <= MAX_AGE_MS;
    }
}
