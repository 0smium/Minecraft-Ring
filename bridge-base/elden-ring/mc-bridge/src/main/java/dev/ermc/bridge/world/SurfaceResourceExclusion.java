package dev.ermc.bridge.world;

import dev.ermc.bridge.CoordMap;
import java.util.Objects;

/** Persistent horizontal exclusions; independent of transient terrain samples and candidate seeds. */
public record SurfaceResourceExclusion(int id, Scope scope, boolean wholeZone,
                                       int x, int z, int radius, String name) {
    public static final int MAX_RULES = 128;
    public static final int MAX_RADIUS = 256;
    public static final int MAX_NAME_LENGTH = 48;
    public static final int WORLD_LIMIT = 30_000_000;

    /** A host process restart keeps this identity; a different map anchor cannot reuse the rule. */
    public record Scope(int zone, int region, double ax, double ay, double az,
                        double unitsPerMeter, boolean flipZ) {
        public Scope {
            if (zone == 0 || !Double.isFinite(ax) || !Double.isFinite(ay) || !Double.isFinite(az)
                    || !Double.isFinite(unitsPerMeter) || unitsPerMeter <= 0) {
                throw new IllegalArgumentException("需要有效且稳定的法环区域坐标映射。");
            }
        }

        public static Scope from(CoordMap.Mapping map) {
            if (map == null || map.provisional()) {
                throw new IllegalArgumentException("当前法环区域坐标尚未稳定，请连接并等待切图完成。");
            }
            return new Scope(map.zone(), map.region(), map.ax(), map.ay(), map.az(),
                map.unitsPerMeter(), map.flipZ());
        }
    }

    public SurfaceResourceExclusion {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(name, "name");
        if (id < 1 || name.isBlank() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7)) {
            throw new IllegalArgumentException("禁区名称需为 1–48 字且不含控制符或格式码。");
        }
        if (wholeZone) {
            if (x != 0 || z != 0 || radius != 0) throw new IllegalArgumentException("区域禁区格式无效。");
        } else if (radius < 1 || radius > MAX_RADIUS
                || Math.abs((long) x) + radius > WORLD_LIMIT || Math.abs((long) z) + radius > WORLD_LIMIT) {
            throw new IllegalArgumentException("半径需为 1–256 格，禁区不得越过 MC 世界坐标边界。");
        }
    }

    /** Inclusive edge: even a canopy edge touching the excluded circle rejects the whole feature. */
    public boolean overlaps(Scope current, int centerX, int centerZ, int footprintRadius) {
        if (footprintRadius < 0 || footprintRadius > MAX_RADIUS) {
            throw new IllegalArgumentException("Invalid resource footprint radius");
        }
        if (!scope.equals(current)) return false;
        if (wholeZone) return true;
        // Block footprints have area, not just centers. Circle center is the marked block center.
        double minX = (double) centerX - footprintRadius;
        double maxX = (double) centerX + footprintRadius + 1;
        double minZ = (double) centerZ - footprintRadius;
        double maxZ = (double) centerZ + footprintRadius + 1;
        double dx = Math.max(minX - (x + .5), Math.max(0, x + .5 - maxX));
        double dz = Math.max(minZ - (z + .5), Math.max(0, z + .5 - maxZ));
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}
