package dev.ermc.bridge.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure, coordinate-stable plans; the caller must prove terrain support and clear space. */
public final class SurfaceResourcePlan {
    public static final int MAX_RADIUS = 128;
    private static final int CELL_SIZE = 16;

    public enum Kind { OAK, BIRCH, FLOWERS, GRASS, BERRIES, ROCK }
    public enum Material {
        GRASS_BLOCK, DIRT, OAK_LOG, OAK_LEAVES, BIRCH_LOG, BIRCH_LEAVES,
        DANDELION, POPPY, SHORT_GRASS, SWEET_BERRY_BUSH, STONE, COAL_ORE
    }
    public record Candidate(long id, int x, int z, Kind kind, long seed) {
        public Candidate { Objects.requireNonNull(kind, "kind"); }
    }
    /** Offsets relative to the candidate's ground block; dy=0 is its support. */
    public record Placement(int dx, int dy, int dz, Material material) {}

    private SurfaceResourcePlan() {}

    /**
     * Circular search with a bounded cost. Height and discovery order are deliberately
     * absent from candidate identity, so a later terrain sample cannot grow it again.
     */
    public static List<Candidate> candidates(long worldSeed, int zone, int region,
                                             int centerX, int centerZ, int radius) {
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be 0.." + MAX_RADIUS);
        }
        long namespace = mix(worldSeed ^ 0x67C9D94BA4596E3BL);
        namespace = mix(namespace ^ Integer.toUnsignedLong(zone));
        namespace = mix(namespace ^ (Integer.toUnsignedLong(region) + 0x9E3779B97F4A7C15L));
        var result = new ArrayList<Candidate>();
        long minX = Math.floorDiv((long) centerX - radius, CELL_SIZE);
        long maxX = Math.floorDiv((long) centerX + radius, CELL_SIZE);
        long minZ = Math.floorDiv((long) centerZ - radius, CELL_SIZE);
        long maxZ = Math.floorDiv((long) centerZ + radius, CELL_SIZE);
        for (long cellX = minX; cellX <= maxX; cellX++) {
            for (long cellZ = minZ; cellZ <= maxZ; cellZ++) {
                long cell = mix(namespace ^ mix(cellX + 0x632BE59BD9B4E019L)
                        ^ mix(cellZ + 0x8CB92BA72F3D8DD7L));
                long tree = mix(cell ^ 0x243F6A8885A308D3L);
                if (pick(tree, 100) < 65) {
                    Kind kind = pick(mix(tree), 5) == 0 ? Kind.BIRCH : Kind.OAK;
                    add(result, cellX, cellZ, 5, 5, tree, kind, centerX, centerZ, radius);
                }
                long flowers = mix(cell ^ 0x13198A2E03707344L);
                add(result, cellX, cellZ, 12, 4, flowers,
                        pick(flowers, 3) == 0 ? Kind.GRASS : Kind.FLOWERS,
                        centerX, centerZ, radius);
                long grass = mix(cell ^ 0xA4093822299F31D0L);
                add(result, cellX, cellZ, 4, 12, grass,
                        pick(grass, 4) == 0 ? Kind.FLOWERS : Kind.GRASS,
                        centerX, centerZ, radius);
                long resource = mix(cell ^ 0x082EFA98EC4E6C89L);
                int roll = pick(resource, 100);
                if (roll < 26) {
                    add(result, cellX, cellZ, 12, 12, resource,
                            roll < 14 ? Kind.BERRIES : Kind.ROCK, centerX, centerZ, radius);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Candidate> out, long cellX, long cellZ, int slotX, int slotZ,
                            long id, Kind kind, int centerX, int centerZ, int radius) {
        long x = cellX * CELL_SIZE + slotX + pick(mix(id), 3) - 1;
        long z = cellZ * CELL_SIZE + slotZ + pick(mix(id ^ 0xD1310BA698DFB5ACL), 3) - 1;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return;
        long dx = x - centerX, dz = z - centerZ;
        if (dx * dx + dz * dz > (long) radius * radius) return;
        out.add(new Candidate(id, (int) x, (int) z, kind, mix(id ^ 0x2FFD72DBD01ADFB7L)));
    }

    /** Layout remains the same when a surface's measured height is refined. */
    public static List<Placement> layout(Candidate candidate, int groundY) {
        Objects.requireNonNull(candidate, "candidate");
        if (groundY > Integer.MAX_VALUE - maxHeight(candidate.kind())) {
            throw new IllegalArgumentException("height overflow");
        }
        var blocks = new ArrayList<Placement>();
        switch (candidate.kind()) {
            case OAK, BIRCH -> tree(blocks, candidate);
            case FLOWERS, GRASS -> plants(blocks, candidate);
            case BERRIES -> {
                put(blocks, 0, 0, 0, Material.GRASS_BLOCK);
                put(blocks, 0, 1, 0, Material.SWEET_BERRY_BUSH);
            }
            case ROCK -> rock(blocks, candidate);
        }
        return List.copyOf(blocks);
    }

    public static int radius(Kind kind) {
        return switch (kind) { case OAK, BIRCH -> 2; default -> 1; };
    }

    public static int maxHeight(Kind kind) {
        return switch (kind) { case OAK, BIRCH -> 6; case ROCK -> 2; default -> 1; };
    }

    private static void tree(List<Placement> blocks, Candidate candidate) {
        boolean birch = candidate.kind() == Kind.BIRCH;
        Material log = birch ? Material.BIRCH_LOG : Material.OAK_LOG;
        Material leaves = birch ? Material.BIRCH_LEAVES : Material.OAK_LEAVES;
        int height = 4 + pick(candidate.seed(), 2);
        put(blocks, 0, 0, 0, Material.DIRT);
        for (int y = 1; y <= height; y++) put(blocks, 0, y, 0, log);
        for (int y = height - 2; y <= height + 1; y++) {
            int radius = y < height ? 2 : 1;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && z == 0 && y <= height) continue;
                    if (Math.abs(x) == radius && Math.abs(z) == radius) continue;
                    put(blocks, x, y, z, leaves);
                }
            }
        }
    }

    private static void plants(List<Placement> blocks, Candidate candidate) {
        plant(blocks, candidate, 0, 0);
        int[][] neighbors = { {-1, 0}, {1, 0}, {0, -1}, {0, 1} };
        int omitted = pick(candidate.seed(), neighbors.length);
        for (int i = 0; i < neighbors.length; i++) {
            if (i == omitted) continue;
            plant(blocks, candidate, neighbors[i][0], neighbors[i][1]);
        }
    }

    private static void plant(List<Placement> blocks, Candidate candidate, int x, int z) {
        Material plant = candidate.kind() == Kind.GRASS ? Material.SHORT_GRASS
                : pick(mix(candidate.seed() ^ ((long) x << 32) ^ z), 2) == 0
                ? Material.DANDELION : Material.POPPY;
        put(blocks, x, 0, z, Material.GRASS_BLOCK);
        put(blocks, x, 1, z, plant);
    }

    private static void rock(List<Placement> blocks, Candidate candidate) {
        int coalColumn = pick(candidate.seed(), 4) == 0 ? pick(mix(candidate.seed()), 4) : -1;
        int highRow = pick(mix(candidate.seed() ^ 0xB8E1AFED6A267E96L), 2);
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                put(blocks, x, 0, z, Material.STONE);
                put(blocks, x, 1, z, x * 2 + z == coalColumn ? Material.COAL_ORE : Material.STONE);
                if (z == highRow) put(blocks, x, 2, z, Material.STONE);
            }
        }
    }

    private static void put(List<Placement> blocks, int x, int y, int z, Material material) {
        blocks.add(new Placement(x, y, z, material));
    }

    private static int pick(long value, int bound) { return (int) Long.remainderUnsigned(value, bound); }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
