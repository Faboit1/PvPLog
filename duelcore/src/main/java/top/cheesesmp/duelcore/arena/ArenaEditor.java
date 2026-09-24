package top.cheesesmp.duelcore.arena;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * Admin flow for building arena templates: create → pos1/pos2 → setspawn1/setspawn2 → save.
 * {@code edit} and {@code import} paste an existing template or a .schem file into the editor world first.
 */
public final class ArenaEditor {

    public static final class Session {
        public final String name;
        public @Nullable Location pos1;
        public @Nullable Location pos2;
        public @Nullable Location spawn1;
        public @Nullable Location spawn2;
        public List<String> tags = new ArrayList<>(List.of("open"));
        public String displayName;
        public int buildHeight = 12;

        Session(String name) {
            this.name = name;
            this.displayName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    private static final int MAX_SIZE_XZ = 256;
    private static final int MAX_SIZE_Y = 160;

    private final DuelCorePlugin plugin;
    private final ArenaManager arenas;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private @Nullable World editorWorld;

    public ArenaEditor(DuelCorePlugin plugin, ArenaManager arenas) {
        this.plugin = plugin;
        this.arenas = arenas;
    }

    public @Nullable Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public Session start(Player player, String name) {
        Session s = new Session(name);
        sessions.put(player.getUniqueId(), s);
        return s;
    }

    public void cancel(Player player) {
        sessions.remove(player.getUniqueId());
    }

    public void forget(UUID uuid) {
        sessions.remove(uuid);
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** The persistent void world used to build templates. */
    public World editorWorld() {
        if (editorWorld != null && Bukkit.getWorld(editorWorld.getUID()) != null) return editorWorld;
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, plugin.settings().editorWorld);
        World w = Bukkit.getWorld(key);
        if (w == null) {
            w = Bukkit.createWorld(WorldCreator.ofKey(key).environment(World.Environment.NORMAL)
                .generator(new VoidGenerator()).generateStructures(false));
            if (w == null) throw new IllegalStateException("could not create editor world");
            ArenaManager.applyRules(w);
            w.setGameRule(org.bukkit.GameRules.BLOCK_DROPS, false);
        }
        if (w.getBlockAt(0, 99, 0).getType().isAir()) w.getBlockAt(0, 99, 0).setType(Material.GLASS);
        editorWorld = w;
        return w;
    }

    /** Checks the session and captures the region. Completes with the saved template name. */
    public CompletableFuture<String> save(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no-session"));
        if (s.pos1 == null || s.pos2 == null) return CompletableFuture.failedFuture(new IllegalStateException("no-region"));
        if (s.spawn1 == null || s.spawn2 == null) return CompletableFuture.failedFuture(new IllegalStateException("no-spawns"));
        World world = s.pos1.getWorld();
        if (world != s.pos2.getWorld() || world != s.spawn1.getWorld() || world != s.spawn2.getWorld()) {
            return CompletableFuture.failedFuture(new IllegalStateException("different-worlds"));
        }
        int minX = Math.min(s.pos1.getBlockX(), s.pos2.getBlockX());
        int minY = Math.min(s.pos1.getBlockY(), s.pos2.getBlockY());
        int minZ = Math.min(s.pos1.getBlockZ(), s.pos2.getBlockZ());
        int maxX = Math.max(s.pos1.getBlockX(), s.pos2.getBlockX());
        int maxY = Math.max(s.pos1.getBlockY(), s.pos2.getBlockY());
        int maxZ = Math.max(s.pos1.getBlockZ(), s.pos2.getBlockZ());
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        if (sx > MAX_SIZE_XZ || sz > MAX_SIZE_XZ || sy > MAX_SIZE_Y) {
            return CompletableFuture.failedFuture(new IllegalStateException("too-big"));
        }
        for (Location spawn : List.of(s.spawn1, s.spawn2)) {
            if (spawn.getX() < minX || spawn.getX() > maxX + 1 || spawn.getZ() < minZ || spawn.getZ() > maxZ + 1
                || spawn.getY() < minY || spawn.getY() > maxY + 1) {
                return CompletableFuture.failedFuture(new IllegalStateException("spawn-outside"));
            }
        }
        RelPos r1 = rel(s.spawn1, minX, minY, minZ);
        RelPos r2 = rel(s.spawn2, minX, minY, minZ);
        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                snapshots.put(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), chunk.getChunkSnapshot(false, false, false, false));
            }
        }
        String name = s.name;
        String display = s.displayName;
        List<String> tags = List.copyOf(s.tags);
        int buildHeight = s.buildHeight;
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                ArenaSnapshot.Builder b = new ArenaSnapshot.Builder(sx, sy, sz);
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        for (int x = 0; x < sx; x++) {
                            int wx = minX + x;
                            int wz = minZ + z;
                            ChunkSnapshot snap = snapshots.get(((long) (wx >> 4) << 32) ^ ((wz >> 4) & 0xFFFFFFFFL));
                            String state = snap.getBlockData(wx & 15, minY + y, wz & 15).getAsString();
                            if (!state.equals(ArenaSnapshot.AIR)) b.set(x, y, z, state);
                        }
                    }
                }
                arenas.writeTemplate(name, display, tags, r1, r2, buildHeight, b.build(), null);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    arenas.loadTemplates();
                    sessions.remove(player.getUniqueId());
                    result.complete(name);
                });
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }, arenas.worker());
        return result;
    }

    private static RelPos rel(Location l, int minX, int minY, int minZ) {
        return new RelPos(l.getX() - minX, l.getY() - minY, l.getZ() - minZ, l.getYaw(), l.getPitch());
    }

    /** Pastes a template into the editor world at the player's position and starts an edit session. */
    public CompletableFuture<Session> edit(Player player, ArenaTemplate template) {
        return pasteForEditing(player, template.name(), template.snapshot(), template.palette(), template.spawn1(),
            template.spawn2(), template.tags(), template.displayName(), template.buildHeight());
    }

    /** Imports plugins/DuelCore/schematics/&lt;file&gt; into the editor world. Spawns must be set afterwards. */
    public CompletableFuture<Session> importSchematic(Player player, String name, String fileName) {
        java.nio.file.Path dir = new File(plugin.getDataFolder(), "schematics").toPath().toAbsolutePath().normalize();
        java.nio.file.Path resolved = dir.resolve(fileName).normalize();
        File file = resolved.toFile();
        if (!dir.equals(resolved.getParent()) || !file.isFile()) {
            return CompletableFuture.failedFuture(new IllegalStateException("no-file"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return SchematicImporter.read(file.toPath());
            } catch (Exception e) {
                throw new IllegalStateException("bad-file: " + e.getMessage(), e);
            }
        }, arenas.worker()).thenCompose(snapshot -> {
            CompletableFuture<Session> f = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<String> errors = new ArrayList<>();
                ArenaTemplate tmp = new ArenaTemplate(name, name, List.of("open"), true,
                    new RelPos(1, 1, 1, 0, 0), new RelPos(1, 1, 1, 0, 0), 12, snapshot, "import", errors);
                pasteForEditing(player, name, snapshot, tmp.palette(), null, null, List.of("open"), name, 12)
                    .whenComplete((s, e) -> {
                        if (e != null) f.completeExceptionally(e);
                        else f.complete(s);
                    });
            });
            return f;
        });
    }

    private CompletableFuture<Session> pasteForEditing(Player player, String name, ArenaSnapshot snapshot,
                                                       org.bukkit.block.data.BlockData[] palette,
                                                       @Nullable RelPos spawn1, @Nullable RelPos spawn2,
                                                       List<String> tags, String displayName, int buildHeight) {
        World world = editorWorld();
        Location base = player.getWorld() == world ? player.getLocation() : new Location(world, 0, 100, 0);
        int ox = base.getBlockX() + 2;
        int oy = Math.max(world.getMinHeight() + 1, base.getBlockY() - 1);
        int oz = base.getBlockZ() + 2;
        RegionJob job = new RegionJob(plugin, world, ox, oy, oz, snapshot, palette, arenas.worker(), false, false);
        arenas.queue().submit(job);
        return job.future().thenApply(r -> {
            Session s = start(player, name);
            s.pos1 = new Location(world, ox, oy, oz);
            s.pos2 = new Location(world, ox + snapshot.sizeX() - 1, oy + snapshot.sizeY() - 1, oz + snapshot.sizeZ() - 1);
            if (spawn1 != null) s.spawn1 = new Location(world, ox + spawn1.x(), oy + spawn1.y(), oz + spawn1.z(), spawn1.yaw(), spawn1.pitch());
            if (spawn2 != null) s.spawn2 = new Location(world, ox + spawn2.x(), oy + spawn2.y(), oz + spawn2.z(), spawn2.yaw(), spawn2.pitch());
            s.tags = new ArrayList<>(tags);
            s.displayName = displayName;
            s.buildHeight = buildHeight;
            if (player.getWorld() != world) player.teleportAsync(new Location(world, ox + snapshot.sizeX() / 2.0,
                oy + snapshot.sizeY() + 2, oz + snapshot.sizeZ() / 2.0));
            return s;
        });
    }
}
