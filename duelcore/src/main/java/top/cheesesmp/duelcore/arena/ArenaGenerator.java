package top.cheesesmp.duelcore.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Built-in arena maps so a fresh install works immediately: patches of natural, mostly flat terrain, each in its
 * own biome (the biome is applied to the instance when it is pasted). They are plain snapshots, written to
 * {@code arenas/<name>.dca} + {@code .yml} on first start and editable afterwards like any other arena.
 *
 * <p>Every map is {@value #SIZE}×{@value #SIZE} blocks, fenced by invisible barrier walls and a barrier ceiling.
 * Hills get gentler towards the middle, the ground around both spawns is levelled, and trees only grow near the
 * edges so the fighting area stays open.
 */
public final class ArenaGenerator {

    public record Generated(String name, String displayName, List<String> tags, ArenaSnapshot snapshot,
                            RelPos spawn1, RelPos spawn2, int buildHeight, String biome) {
    }

    static final int SIZE = 64;
    static final int HEIGHT = 56;
    /** Mean surface height inside the snapshot. */
    static final int GROUND = 10;
    static final int SPAWN_Z1 = 16;
    static final int SPAWN_Z2 = SIZE - 1 - SPAWN_Z1;
    static final List<String> TAGS = List.of("terrain");

    private static final String AIR = "minecraft:air";
    private static final String BARRIER = "minecraft:barrier";

    private enum Tree { OAK, BIRCH, SPRUCE, CHERRY, ACACIA, CACTUS }

    /** Block for a terrain column: {@code depth} 0 is the surface, 1 the block below it, and so on. */
    @FunctionalInterface
    private interface Column {
        String block(int x, int y, int z, int depth, double patch, SplittableRandom r);
    }

    /** Plant or snow on top of the surface block, or null. */
    @FunctionalInterface
    private interface Deco {
        String block(String surface, double patch, SplittableRandom r);
    }

    private record Style(String name, String display, String biome, long seed, double hills, Column column, Deco deco,
                         List<Tree> trees, int treeCount) {
    }

    private ArenaGenerator() {
    }

    public static List<Generated> defaults() {
        List<Generated> list = new ArrayList<>();
        for (Style s : styles()) list.add(generate(s));
        return list;
    }

    private static List<Style> styles() {
        String[] flowers = {"minecraft:dandelion", "minecraft:poppy", "minecraft:oxeye_daisy", "minecraft:cornflower",
            "minecraft:azure_bluet"};
        String[] bands = {"minecraft:terracotta", "minecraft:orange_terracotta", "minecraft:yellow_terracotta",
            "minecraft:terracotta", "minecraft:red_terracotta", "minecraft:white_terracotta", "minecraft:orange_terracotta",
            "minecraft:brown_terracotta", "minecraft:light_gray_terracotta"};
        String[] facings = {"north", "east", "south", "west"};
        return List.of(
            new Style("greenfield", "Greenfield", "minecraft:plains", 11, 3.0,
                (x, y, z, d, patch, r) -> d == 0 ? "minecraft:grass_block[snowy=false]" : d <= 3 ? "minecraft:dirt" : stone(r),
                (surface, patch, r) -> {
                    double v = r.nextDouble();
                    if (v < 0.20) return "minecraft:short_grass";
                    if (v < 0.23) return flowers[r.nextInt(flowers.length)];
                    return null;
                }, List.of(Tree.OAK, Tree.OAK, Tree.BIRCH), 9),
            new Style("dunes", "Dunes", "minecraft:desert", 23, 3.5,
                (x, y, z, d, patch, r) -> d <= 2 ? "minecraft:sand" : d <= 6 ? "minecraft:sandstone" : stone(r),
                (surface, patch, r) -> {
                    double v = r.nextDouble();
                    if (v < 0.02) return "minecraft:dead_bush";
                    if (v < 0.05) return "minecraft:short_dry_grass";
                    return null;
                }, List.of(Tree.CACTUS), 10),
            new Style("tundra", "Tundra", "minecraft:snowy_plains", 37, 2.5,
                (x, y, z, d, patch, r) -> d == 0 ? "minecraft:grass_block[snowy=true]" : d <= 3 ? "minecraft:dirt" : stone(r),
                (surface, patch, r) -> "minecraft:snow[layers=1]", List.of(Tree.SPRUCE), 9),
            new Style("mesa", "Mesa", "minecraft:badlands", 41, 3.5,
                (x, y, z, d, patch, r) -> d <= 1 ? "minecraft:red_sand" : bands[Math.floorMod(y, bands.length)],
                (surface, patch, r) -> r.nextDouble() < 0.025 ? "minecraft:dead_bush" : null, List.of(Tree.CACTUS), 6),
            new Style("blossom", "Blossom", "minecraft:cherry_grove", 53, 3.0,
                (x, y, z, d, patch, r) -> d == 0 ? "minecraft:grass_block[snowy=false]" : d <= 3 ? "minecraft:dirt" : stone(r),
                (surface, patch, r) -> {
                    double v = r.nextDouble();
                    if (v < 0.18) {
                        return "minecraft:pink_petals[facing=" + facings[r.nextInt(4)] + ",flower_amount=" + (1 + r.nextInt(4)) + "]";
                    }
                    if (v < 0.24) return "minecraft:short_grass";
                    return null;
                }, List.of(Tree.CHERRY), 8),
            new Style("savanna", "Savanna", "minecraft:savanna", 67, 2.5,
                (x, y, z, d, patch, r) -> d == 0 ? (patch > 0.35 ? "minecraft:coarse_dirt" : "minecraft:grass_block[snowy=false]")
                    : d <= 3 ? "minecraft:dirt" : stone(r),
                (surface, patch, r) -> surface.startsWith("minecraft:grass_block") && r.nextDouble() < 0.28
                    ? "minecraft:short_grass" : null, List.of(Tree.ACACIA), 6),
            new Style("pinewood", "Pinewood", "minecraft:taiga", 79, 3.0,
                (x, y, z, d, patch, r) -> d == 0
                    ? (patch > 0.3 ? "minecraft:podzol[snowy=false]" : patch < -0.45 ? "minecraft:coarse_dirt" : "minecraft:grass_block[snowy=false]")
                    : d <= 3 ? "minecraft:dirt" : stone(r),
                (surface, patch, r) -> {
                    if (surface.startsWith("minecraft:coarse_dirt")) return null;
                    double v = r.nextDouble();
                    if (v < 0.10) return "minecraft:fern";
                    if (v < 0.14) return "minecraft:leaf_litter[facing=" + facings[r.nextInt(4)] + ",segment_amount=" + (1 + r.nextInt(4)) + "]";
                    return null;
                }, List.of(Tree.SPRUCE), 12));
    }

    private static String stone(SplittableRandom r) {
        double v = r.nextDouble();
        return v < 0.06 ? "minecraft:andesite" : v < 0.09 ? "minecraft:gravel" : "minecraft:stone";
    }

    // ------------------------------------------------------------------ generation

    private static Generated generate(Style s) {
        SplittableRandom r = new SplittableRandom(s.seed());
        int[][] h = heights(s);
        String[] grid = new String[SIZE * HEIGHT * SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int top = h[x][z];
                double patch = noise(s.seed() + 99, x / 7.0, z / 7.0);
                set(grid, x, 0, z, "minecraft:bedrock");
                for (int y = 1; y <= top; y++) set(grid, x, y, z, s.column().block(x, y, z, top - y, patch, r));
            }
        }
        // decorations first, trees overwrite them
        for (int x = 1; x < SIZE - 1; x++) {
            for (int z = 1; z < SIZE - 1; z++) {
                int top = h[x][z];
                String deco = s.deco().block(get(grid, x, top, z), noise(s.seed() + 99, x / 7.0, z / 7.0), r);
                if (deco != null) set(grid, x, top + 1, z, deco);
            }
        }
        plantTrees(s, grid, h, r);
        // invisible fence: walls on the border columns and a ceiling
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                boolean edge = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                if (edge) for (int y = h[x][z] + 1; y < HEIGHT; y++) set(grid, x, y, z, BARRIER);
                set(grid, x, HEIGHT - 1, z, BARRIER);
            }
        }
        ArenaSnapshot.Builder b = new ArenaSnapshot.Builder(SIZE, HEIGHT, SIZE);
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    String block = get(grid, x, y, z);
                    if (block != null) b.set(x, y, z, block);
                }
            }
        }
        int y1 = h[SIZE / 2][SPAWN_Z1] + 1;
        int y2 = h[SIZE / 2][SPAWN_Z2] + 1;
        int buildHeight = 24;
        return new Generated(s.name(), s.display(), TAGS, b.build(),
            new RelPos(SIZE / 2.0, y1, SPAWN_Z1 + 0.5, 0, 0), new RelPos(SIZE / 2.0, y2, SPAWN_Z2 + 0.5, 180, 0),
            buildHeight, s.biome());
    }

    /** Surface height per column: two octaves of value noise, damped towards the middle, levelled at the spawns. */
    private static int[][] heights(Style s) {
        double[][] raw = new double[SIZE][SIZE];
        double c = (SIZE - 1) / 2.0;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                double n = 0.65 * noise(s.seed(), x / 14.0, z / 14.0) + 0.35 * noise(s.seed() + 7, x / 6.0, z / 6.0);
                double dist = Math.hypot(x - c, z - c);
                double amp = 0.45 + 0.55 * Math.clamp((dist - 12) / 14.0, 0, 1);
                raw[x][z] = GROUND + s.hills() * amp * n;
            }
        }
        int[][] h = new int[SIZE][SIZE];
        int[][] spawns = {{SIZE / 2, SPAWN_Z1}, {SIZE / 2, SPAWN_Z2}};
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                double v = raw[x][z];
                for (int[] sp : spawns) {
                    double level = Math.round((raw[sp[0]][sp[1]] + raw[sp[0] - 1][sp[1]]) / 2);
                    double d = Math.hypot(x + 0.5 - sp[0], z + 0.5 - (sp[1] + 0.5));
                    if (d <= 3.5) v = level;
                    else if (d < 7) v = level + (v - level) * (d - 3.5) / 3.5;
                }
                h[x][z] = (int) Math.clamp(Math.round(v), 3, HEIGHT - 20);
            }
        }
        return h;
    }

    private static void plantTrees(Style s, String[] grid, int[][] h, SplittableRandom r) {
        if (s.treeCount() <= 0 || s.trees().isEmpty()) return;
        List<int[]> placed = new ArrayList<>();
        double c = (SIZE - 1) / 2.0;
        for (int attempt = 0; attempt < 600 && placed.size() < s.treeCount(); attempt++) {
            int x = 4 + r.nextInt(SIZE - 8);
            int z = 4 + r.nextInt(SIZE - 8);
            if (Math.hypot(x - c, z - c) < 21) continue; // keep the middle open
            if (Math.hypot(x - SIZE / 2.0, z - SPAWN_Z1) < 11 || Math.hypot(x - SIZE / 2.0, z - SPAWN_Z2) < 11) continue;
            boolean crowded = false;
            for (int[] p : placed) {
                if (Math.abs(p[0] - x) + Math.abs(p[1] - z) < 7) {
                    crowded = true;
                    break;
                }
            }
            if (crowded) continue;
            Tree tree = s.trees().get(r.nextInt(s.trees().size()));
            int base = h[x][z] + 1;
            switch (tree) {
                case OAK -> roundTree(grid, x, base, z, 4 + r.nextInt(2), "minecraft:oak_log[axis=y]",
                    "minecraft:oak_leaves[persistent=true]", r);
                case BIRCH -> roundTree(grid, x, base, z, 5 + r.nextInt(2), "minecraft:birch_log[axis=y]",
                    "minecraft:birch_leaves[persistent=true]", r);
                case SPRUCE -> spruce(grid, x, base, z, 7 + r.nextInt(3), s.biome().contains("snowy"));
                case CHERRY -> cherry(grid, x, base, z, 4 + r.nextInt(2));
                case ACACIA -> acacia(grid, x, base, z, r);
                case CACTUS -> {
                    int height = 2 + r.nextInt(2);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) set(grid, x + dx, base, z + dz, AIR);
                    }
                    for (int y = 0; y < height; y++) set(grid, x, base + y, z, "minecraft:cactus[age=0]");
                }
            }
            placed.add(new int[] {x, z});
        }
    }

    private static void roundTree(String[] grid, int x, int y, int z, int height, String log, String leaves,
                                  SplittableRandom r) {
        for (int i = 0; i < height; i++) set(grid, x, y + i, z, log);
        for (int dy = height - 2; dy <= height + 1; dy++) {
            int radius = dy < height ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                    if (corner && (dy == height + 1 || r.nextBoolean())) continue;
                    leaf(grid, x + dx, y + dy, z + dz, leaves);
                }
            }
        }
    }

    private static void spruce(String[] grid, int x, int y, int z, int height, boolean snowy) {
        String leaves = "minecraft:spruce_leaves[persistent=true]";
        for (int i = 0; i < height; i++) set(grid, x, y + i, z, "minecraft:spruce_log[axis=y]");
        int top = y + height;
        leaf(grid, x, top, z, leaves);
        if (snowy) set(grid, x, top + 1, z, "minecraft:snow[layers=1]");
        for (int i = 1; top - i >= y + 2; i++) {
            int radius = i <= 2 ? 1 : (i % 2 == 1 ? 1 : 2);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + (radius == 2 ? 1 : 0)) continue;
                    leaf(grid, x + dx, top - i, z + dz, leaves);
                }
            }
        }
    }

    private static void cherry(String[] grid, int x, int y, int z, int height) {
        String leaves = "minecraft:cherry_leaves[persistent=true]";
        for (int i = 0; i < height; i++) set(grid, x, y + i, z, "minecraft:cherry_log[axis=y]");
        int top = y + height;
        for (int dy = -1; dy <= 1; dy++) {
            int radius = dy == 1 ? 2 : 3;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + 1) continue;
                    leaf(grid, x + dx, top + dy, z + dz, leaves);
                }
            }
        }
    }

    private static void acacia(String[] grid, int x, int y, int z, SplittableRandom r) {
        String log = "minecraft:acacia_log[axis=y]";
        String leaves = "minecraft:acacia_leaves[persistent=true]";
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] d = dirs[r.nextInt(4)];
        for (int i = 0; i < 3; i++) set(grid, x, y + i, z, log);
        int cx = x + d[0];
        int cz = z + d[1];
        set(grid, cx, y + 3, cz, log);
        cx += d[0];
        cz += d[1];
        set(grid, cx, y + 4, cz, log);
        int top = y + 5;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 4) continue;
                leaf(grid, cx + dx, top, cz + dz, leaves);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) leaf(grid, cx + dx, top + 1, cz + dz, leaves);
        }
    }

    private static void leaf(String[] grid, int x, int y, int z, String leaves) {
        if (x < 1 || z < 1 || x > SIZE - 2 || z > SIZE - 2 || y >= HEIGHT - 1) return;
        String existing = get(grid, x, y, z);
        if (existing == null || !existing.contains("_log")) set(grid, x, y, z, leaves);
    }

    private static void set(String[] grid, int x, int y, int z, String block) {
        if (x < 0 || z < 0 || y < 0 || x >= SIZE || z >= SIZE || y >= HEIGHT) return;
        grid[(y * SIZE + z) * SIZE + x] = block;
    }

    private static String get(String[] grid, int x, int y, int z) {
        if (x < 0 || z < 0 || y < 0 || x >= SIZE || z >= SIZE || y >= HEIGHT) return null;
        return grid[(y * SIZE + z) * SIZE + x];
    }

    // ------------------------------------------------------------------ noise

    /** Smooth value noise in [-1, 1]. */
    static double noise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);
        double a = lerp(hash(seed, x0, z0), hash(seed, x0 + 1, z0), sx);
        double b = lerp(hash(seed, x0, z0 + 1), hash(seed, x0 + 1, z0 + 1), sx);
        return lerp(a, b, sz);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double hash(long seed, int x, int z) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53 * 2 - 1;
    }
}
