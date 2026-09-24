package top.cheesesmp.duelcore.arena;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.MainConfig;

/**
 * Owns the arena world, the templates and every instance. Matches {@link #acquire} an instance, the manager pastes
 * or reuses one, and {@link #release} resets it (or clears and unloads it when the pool is full).
 */
public final class ArenaManager {

    private record Pending(List<String> tags, CompletableFuture<ArenaInstance> future) {
    }

    /** What a slot holds on disk: enough to reuse it after a restart, or to clear it if the template changed. */
    private record SlotRecord(String template, String stamp, int ox, int oy, int oz, int sx, int sy, int sz) {
    }

    /** Lives in the arena world folder, so deleting the world also deletes the manifest. */
    private static final String MANIFEST = "duelcore-slots.yml";
    private static final int PREGEN_IN_FLIGHT = 8;

    private final DuelCorePlugin plugin;
    private final ExecutorService worker;
    private final BlockJobQueue queue;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, ArenaTemplate> templates = new LinkedHashMap<>();
    private final List<ArenaInstance> instances = new ArrayList<>();
    private final Map<Integer, ArenaInstance> bySlot = new HashMap<>();
    private final Map<String, Deque<ArenaInstance>> idle = new HashMap<>();
    private final Deque<Pending> waiting = new ArrayDeque<>();
    private final Map<Integer, SlotRecord> manifest = new java.util.TreeMap<>();
    private SlotGrid slots;
    private World world;
    private @Nullable Path manifestFile;
    private boolean persistent;
    private org.bukkit.scheduler.@Nullable BukkitTask pregenTask;
    private String pregenKey = "";
    private int pregenSlots;
    private long pregenChunks;
    private long restored;
    private long pastes;
    private long resets;
    private long clears;
    private long dirtyResets;

    public ArenaManager(DuelCorePlugin plugin) {
        this.plugin = plugin;
        AtomicInteger n = new AtomicInteger();
        this.worker = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "DuelCore-Arena-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.queue = new BlockJobQueue(plugin.getLogger(), plugin.settings().blockBudgetMs);
    }

    public BlockJobQueue queue() {
        return queue;
    }

    public World world() {
        return world;
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        MainConfig cfg = plugin.settings();
        this.slots = new SlotGrid(cfg.slotSpacing);
        this.persistent = cfg.arenaPersistentWorld;
        this.world = createArenaWorld(cfg.arenaWorld);
        loadTemplates();
        restoreSlots();
        if (cfg.prewarm) {
            for (ArenaTemplate t : templates.values()) {
                if (t.enabled()) prewarm(t);
            }
        }
        startPregeneration();
    }

    /**
     * Creates or loads the void arena world (26.x keyed world, stored in {@code <level>/dimensions/duelcore/<key>}).
     * A persistent world is kept between restarts together with its slot manifest; a world without a manifest (or
     * any world when persistence is off) is deleted first because its contents are unknown.
     */
    private World createArenaWorld(String keyPath) {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, keyPath);
        Path folder = Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getKey());
        World existing = Bukkit.getWorld(key);
        if (existing != null) {
            folder = existing.getWorldPath();
            Bukkit.unloadWorld(existing, persistent);
        }
        boolean wipe = !persistent || !Files.exists(folder.resolve(MANIFEST));
        if (wipe && Files.isDirectory(folder)) {
            plugin.getLogger().info("Arena world " + key + (persistent ? " has no slot manifest, recreating it" : " is recreated on every start"));
            deleteFolder(folder);
        }
        WorldCreator creator = WorldCreator.ofKey(key)
            .environment(World.Environment.NORMAL)
            .generator(new VoidGenerator())
            .generateStructures(false);
        World w = Bukkit.createWorld(creator);
        if (w == null) throw new IllegalStateException("could not create world " + key);
        applyRules(w);
        int view = plugin.settings().arenaViewDistance;
        w.setViewDistance(view);
        w.setSendViewDistance(view);
        w.setSimulationDistance(Math.max(2, Math.min(view, 4)));
        w.setAutoSave(persistent);
        manifestFile = w.getWorldPath().resolve(MANIFEST);
        return w;
    }

    public static void applyRules(World w) {
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.SPAWN_MONSTERS, false);
        w.setGameRule(GameRules.SPAWN_PATROLS, false);
        w.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        w.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        w.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        w.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        w.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRules.KEEP_INVENTORY, true);
        w.setGameRule(GameRules.ENTITY_DROPS, false);
        w.setGameRule(GameRules.BLOCK_DROPS, false);
        w.setGameRule(GameRules.MOB_GRIEFING, true);
        w.setGameRule(GameRules.LOCATOR_BAR, false);
        w.setGameRule(GameRules.PVP, true);
        w.setDifficulty(Difficulty.NORMAL);
        w.setTime(6000);
        w.setStorm(false);
        w.setThundering(false);
        w.setClearWeatherDuration(Integer.MAX_VALUE);
    }

    public void disable() {
        if (pregenTask != null) pregenTask.cancel();
        queue.cancelAll();
        worker.shutdownNow();
        for (Pending p : waiting) p.future().cancel(false);
        waiting.clear();
        instances.clear();
        bySlot.clear();
        idle.clear();
        if (world != null) {
            Path folder = world.getWorldPath();
            for (org.bukkit.entity.Player p : world.getPlayers()) {
                p.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            }
            world.removePluginChunkTickets(plugin);
            if (persistent) {
                // unfinished pastes/resets are fixed by the verify pass on the next start
                writeManifest(manifestText());
                Bukkit.unloadWorld(world, true);
            } else {
                Bukkit.unloadWorld(world, false);
                deleteFolder(folder);
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Re-adopts the slots recorded in the manifest. Slots whose template is unchanged are verified against it (a
     * normal diff pass: after a clean shutdown nothing needs fixing) and become idle instances; the rest are cleared.
     */
    private void restoreSlots() {
        if (manifestFile == null || !Files.exists(manifestFile)) {
            saveManifest();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(manifestFile.toFile());
        pregenKey = y.getString("pregenerated.key", "");
        pregenSlots = y.getInt("pregenerated.slots", 0);
        ConfigurationSection section = y.getConfigurationSection("slots");
        if (section == null) return;
        Map<String, Integer> kept = new HashMap<>();
        int reuse = 0;
        int clear = 0;
        for (String k : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(k);
            int slot;
            try {
                slot = Integer.parseInt(k);
            } catch (NumberFormatException e) {
                continue;
            }
            if (r == null || slot < 0) continue;
            SlotRecord rec = new SlotRecord(r.getString("template", ""), r.getString("stamp", ""), r.getInt("x"),
                r.getInt("y"), r.getInt("z"), r.getInt("sx"), r.getInt("sy"), r.getInt("sz"));
            slots.reserve(slot);
            manifest.put(slot, rec);
            ArenaTemplate t = templates.get(rec.template());
            boolean reusable = t != null && t.enabled() && t.stamp().equals(rec.stamp())
                && rec.equals(record(t, slot)) && kept.getOrDefault(t.name(), 0) < plugin.settings().keepIdlePerTemplate;
            if (reusable) {
                kept.merge(t.name(), 1, Integer::sum);
                reuse++;
                paste(t, slot).thenAccept(instance -> {
                    restored++;
                    if (handToWaiting(instance)) return;
                    instance.state(ArenaInstance.State.READY);
                    idleOf(t).addLast(instance);
                    servePending();
                });
            } else {
                clear++;
                clearSlot(slot, rec);
            }
        }
        plugin.getLogger().info("Arena world: reusing " + reuse + " pasted arenas, clearing " + clear + " stale slots");
    }

    private SlotRecord record(ArenaTemplate t, int slot) {
        return new SlotRecord(t.name(), t.stamp(), slots.originX(slot) - t.sizeX() / 2, plugin.settings().arenaBaseY,
            slots.originZ(slot) - t.sizeZ() / 2, t.sizeX(), t.sizeY(), t.sizeZ());
    }

    /** Clears a slot left over from an earlier run (template changed or no longer needed) and frees it. */
    private void clearSlot(int slot, SlotRecord rec) {
        ArenaSnapshot empty = ArenaSnapshot.empty(rec.sx(), rec.sy(), rec.sz());
        BlockData[] air = {Bukkit.createBlockData(org.bukkit.Material.AIR)};
        RegionJob job = new RegionJob(plugin, world, rec.ox(), rec.oy(), rec.oz(), empty, air, worker, false, true);
        queue.submit(job);
        job.future().whenComplete((r, e) -> {
            if (e != null) {
                plugin.getLogger().log(Level.WARNING, "Clearing stale arena slot " + slot + " failed", e);
                return; // keep it reserved and recorded; the next start tries again
            }
            clears++;
            manifest.remove(slot);
            slots.release(slot);
            saveManifest();
            verbose("cleared stale slot " + slot + " (" + rec.template() + ", " + r.blocksChanged() + " blocks)");
            servePending();
        });
    }

    private String manifestText() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("DuelCore arena slots. Generated file, do not edit: delete the whole arena world",
            "folder instead to start from scratch."));
        y.set("pregenerated.key", pregenKey);
        y.set("pregenerated.slots", pregenSlots);
        for (Map.Entry<Integer, SlotRecord> e : manifest.entrySet()) {
            SlotRecord r = e.getValue();
            String base = "slots." + e.getKey() + ".";
            y.set(base + "template", r.template());
            y.set(base + "stamp", r.stamp());
            y.set(base + "x", r.ox());
            y.set(base + "y", r.oy());
            y.set(base + "z", r.oz());
            y.set(base + "sx", r.sx());
            y.set(base + "sy", r.sy());
            y.set(base + "sz", r.sz());
        }
        if (manifest.isEmpty()) y.createSection("slots");
        return y.saveToString();
    }

    /** Snapshot on the calling (main) thread, write off-thread. Writes are ordered by the single-file lock. */
    private void saveManifest() {
        if (!persistent || manifestFile == null) return;
        String text = manifestText();
        try {
            worker.execute(() -> writeManifest(text));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            writeManifest(text);
        }
    }

    private synchronized void writeManifest(String text) {
        if (!persistent || manifestFile == null) return;
        try {
            Path tmp = manifestFile.resolveSibling(MANIFEST + ".tmp");
            Files.createDirectories(manifestFile.getParent());
            Files.writeString(tmp, text);
            Files.move(tmp, manifestFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save the arena slot manifest", e);
        }
    }

    /**
     * Generates the chunks of the first {@code arena.pregenerate-slots} slots in the background (a few chunk loads at
     * a time, non-urgent), so pastes later only read chunks from disk instead of waiting for world generation.
     * Covers each slot's largest possible footprint plus the arena view distance.
     */
    private void startPregeneration() {
        int target = plugin.settings().arenaPregenerateSlots;
        if (!persistent || target <= 0 || templates.isEmpty()) return;
        int maxX = 0;
        int maxZ = 0;
        for (ArenaTemplate t : templates.values()) {
            maxX = Math.max(maxX, t.sizeX());
            maxZ = Math.max(maxZ, t.sizeZ());
        }
        int radius = plugin.settings().arenaViewDistance;
        String key = maxX + "x" + maxZ + "r" + radius + "s" + plugin.settings().slotSpacing;
        if (!key.equals(pregenKey)) {
            pregenKey = key;
            pregenSlots = 0;
        }
        if (pregenSlots >= target) return;
        final int halfX = maxX / 2 + 1;
        final int halfZ = maxZ / 2 + 1;
        Deque<long[]> todo = new ArrayDeque<>();
        for (int slot = pregenSlots; slot < target; slot++) {
            int cx0 = (slots.originX(slot) - halfX >> 4) - radius;
            int cx1 = (slots.originX(slot) + halfX >> 4) + radius;
            int cz0 = (slots.originZ(slot) - halfZ >> 4) - radius;
            int cz1 = (slots.originZ(slot) + halfZ >> 4) + radius;
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) todo.add(new long[] {cx, cz, slot});
            todo.add(new long[] {Long.MIN_VALUE, 0, slot}); // marker: slot finished once everything before it is done
        }
        int total = todo.size() - (target - pregenSlots);
        long start = System.currentTimeMillis();
        AtomicInteger inFlight = new AtomicInteger();
        plugin.getLogger().info("Pre-generating arena chunks for slots " + pregenSlots + ".." + (target - 1) + " (" + total
            + " chunks, background)");
        pregenTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            while (!todo.isEmpty() && inFlight.get() < PREGEN_IN_FLIGHT) {
                long[] c = todo.peekFirst();
                if (c[0] == Long.MIN_VALUE) {
                    if (inFlight.get() > 0) break; // wait until the slot's chunks are all done
                    todo.pollFirst();
                    pregenSlots = (int) c[2] + 1;
                    saveManifest();
                    continue;
                }
                todo.pollFirst();
                inFlight.incrementAndGet();
                world.getChunkAtAsync((int) c[0], (int) c[1], true, false).whenComplete((chunk, err) -> {
                    inFlight.decrementAndGet();
                    if (err == null) pregenChunks++;
                });
            }
            if (todo.isEmpty() && inFlight.get() == 0) {
                plugin.getLogger().info("Arena chunks pre-generated: " + pregenChunks + " chunks in "
                    + (System.currentTimeMillis() - start) / 1000 + " s");
                world.save();
                if (pregenTask != null) pregenTask.cancel();
                pregenTask = null;
            }
        }, 40L, 1L);
    }

    private static void deleteFolder(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort; locked files are overwritten next start
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ templates

    public File arenaFolder() {
        return new File(plugin.getDataFolder(), "arenas");
    }

    /** (Re)loads every arena template. Instances of changed templates are rebuilt after their current match. */
    public List<String> loadTemplates() {
        List<String> errors = new ArrayList<>();
        File dir = arenaFolder();
        if (!dir.isDirectory()) dir.mkdirs();
        File[] ymls = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (ymls == null || ymls.length == 0) {
            for (ArenaGenerator.Generated g : ArenaGenerator.defaults()) {
                try {
                    writeTemplate(g.name(), g.displayName(), g.tags(), g.spawn1(), g.spawn2(), g.buildHeight(), g.snapshot(),
                        g.biome());
                } catch (IOException e) {
                    errors.add("could not write default arena " + g.name() + ": " + e.getMessage());
                }
            }
            ymls = dir.listFiles((d, n) -> n.endsWith(".yml"));
        }
        Map<String, ArenaTemplate> loaded = new LinkedHashMap<>();
        if (ymls != null) {
            for (File yml : ymls) {
                String name = yml.getName().substring(0, yml.getName().length() - 4).toLowerCase(Locale.ROOT);
                try {
                    ArenaTemplate t = readTemplate(name, yml, errors);
                    if (t != null) loaded.put(name, t);
                } catch (Exception e) {
                    errors.add("arena " + name + ": " + e.getMessage());
                }
            }
        }
        templates.clear();
        templates.putAll(loaded);
        // drop idle instances whose template changed or vanished
        for (Iterator<Map.Entry<String, Deque<ArenaInstance>>> it = idle.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Deque<ArenaInstance>> e = it.next();
            ArenaTemplate current = templates.get(e.getKey());
            for (Iterator<ArenaInstance> inst = e.getValue().iterator(); inst.hasNext(); ) {
                ArenaInstance i = inst.next();
                if (current != i.template()) {
                    inst.remove();
                    destroy(i);
                }
            }
        }
        for (String e : errors) plugin.getLogger().warning("Arena problem: " + e);
        plugin.getLogger().info("Loaded " + templates.size() + " arena templates");
        return errors;
    }

    private @Nullable ArenaTemplate readTemplate(String name, File yml, List<String> errors) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(yml);
        } catch (Exception e) {
            throw new IOException("invalid yml: " + e.getMessage());
        }
        File data = new File(yml.getParentFile(), name + ".dca");
        ArenaSnapshot snapshot;
        if (data.exists()) {
            snapshot = ArenaSnapshot.read(data.toPath());
        } else if (y.getString("schematic") != null) {
            snapshot = SchematicImporter.read(new File(yml.getParentFile(), y.getString("schematic")).toPath());
        } else {
            errors.add("arena " + name + ": missing " + data.getName());
            return null;
        }
        RelPos s1 = pos(y.getConfigurationSection("spawn1"));
        RelPos s2 = pos(y.getConfigurationSection("spawn2"));
        if (s1 == null || s2 == null) {
            errors.add("arena " + name + ": spawn1/spawn2 missing");
            return null;
        }
        if (y.getBoolean("bedrock-floor", true) && !snapshot.hasBedrockFloor()) {
            // nothing can be mined through into the void: add a bedrock layer under the arena, spawns move up with it
            snapshot = snapshot.withBedrockFloor();
            s1 = new RelPos(s1.x(), s1.y() + 1, s1.z(), s1.yaw(), s1.pitch());
            s2 = new RelPos(s2.x(), s2.y() + 1, s2.z(), s2.yaw(), s2.pitch());
        }
        File source = data.exists() ? data : new File(yml.getParentFile(), y.getString("schematic", ""));
        org.bukkit.block.@Nullable Biome biome = null;
        String biomeName = y.getString("biome", "");
        if (!biomeName.isBlank()) {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(biomeName.toLowerCase(Locale.ROOT));
            biome = key == null ? null : io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME).get(key);
            if (biome == null) errors.add("arena " + name + ": unknown biome '" + biomeName + "'");
        }
        return new ArenaTemplate(name, y.getString("display-name", name), y.getStringList("tags"),
            y.getBoolean("enabled", true), s1, s2, y.getInt("build-height", 12), snapshot, stamp(source), errors)
            .biome(biome);
    }

    /** CRC32 + length of the block data file: changes whenever the arena's blocks change. */
    private static String stamp(File file) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] bytes = Files.readAllBytes(file.toPath());
        crc.update(bytes);
        return Long.toHexString(crc.getValue()) + "-" + bytes.length;
    }

    private static @Nullable RelPos pos(@Nullable ConfigurationSection s) {
        if (s == null) return null;
        return new RelPos(s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"),
            (float) s.getDouble("pitch"));
    }

    public void writeTemplate(String name, String displayName, List<String> tags, RelPos s1, RelPos s2, int buildHeight,
                              ArenaSnapshot snapshot, @Nullable String biome) throws IOException {
        File dir = arenaFolder();
        dir.mkdirs();
        snapshot.write(new File(dir, name + ".dca").toPath());
        YamlConfiguration y = new YamlConfiguration();
        File yml = new File(dir, name + ".yml");
        if (yml.exists()) {
            try {
                y.load(yml);
            } catch (Exception ignored) {
                // overwrite a broken file
            }
        }
        y.set("display-name", displayName);
        y.set("tags", tags);
        if (!y.contains("enabled")) y.set("enabled", true);
        y.set("build-height", buildHeight);
        if (biome != null) y.set("biome", biome);
        else if (!y.contains("biome")) y.set("biome", "minecraft:plains");
        y.set("size", snapshot.sizeX() + "x" + snapshot.sizeY() + "x" + snapshot.sizeZ());
        setPos(y, "spawn1", s1);
        setPos(y, "spawn2", s2);
        y.save(yml);
    }

    private static void setPos(YamlConfiguration y, String path, RelPos p) {
        y.set(path + ".x", p.x());
        y.set(path + ".y", p.y());
        y.set(path + ".z", p.z());
        y.set(path + ".yaw", (double) p.yaw());
        y.set(path + ".pitch", (double) p.pitch());
    }

    public Collection<ArenaTemplate> templates() {
        return templates.values();
    }

    public @Nullable ArenaTemplate template(String name) {
        return templates.get(name.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ pooling

    public List<ArenaTemplate> candidates(List<String> tags) {
        List<ArenaTemplate> list = new ArrayList<>();
        for (ArenaTemplate t : templates.values()) if (t.enabled() && t.matches(tags)) list.add(t);
        if (list.isEmpty()) {
            for (ArenaTemplate t : templates.values()) if (t.enabled()) list.add(t);
        }
        return list;
    }

    /** Gets an arena for a kit's tags: an idle instance if any, otherwise a fresh paste (or waits for a free one). */
    public CompletableFuture<ArenaInstance> acquire(List<String> tags) {
        List<ArenaTemplate> candidates = candidates(tags);
        if (candidates.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("no arenas configured"));
        List<ArenaTemplate> withIdle = candidates.stream().filter(t -> !idleOf(t).isEmpty()).toList();
        if (!withIdle.isEmpty()) {
            ArenaTemplate t = withIdle.get(ThreadLocalRandom.current().nextInt(withIdle.size()));
            ArenaInstance instance = idleOf(t).pollFirst();
            instance.state(ArenaInstance.State.IN_USE);
            instance.markUsed();
            return CompletableFuture.completedFuture(instance);
        }
        if (liveInstances() >= plugin.settings().maxInstances) {
            CompletableFuture<ArenaInstance> future = new CompletableFuture<>();
            waiting.addLast(new Pending(List.copyOf(tags), future));
            return future;
        }
        ArenaTemplate t = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        return create(t).thenApply(instance -> {
            instance.state(ArenaInstance.State.IN_USE);
            instance.markUsed();
            return instance;
        });
    }

    private void prewarm(ArenaTemplate template) {
        if (!idleOf(template).isEmpty() || liveInstances() >= plugin.settings().maxInstances) return;
        for (ArenaInstance i : instances) {
            if (i.template() == template && i.state() == ArenaInstance.State.PASTING) return; // being restored
        }
        create(template).thenAccept(instance -> {
            instance.state(ArenaInstance.State.READY);
            idleOf(template).addLast(instance);
            servePending();
        });
    }

    private CompletableFuture<ArenaInstance> create(ArenaTemplate template) {
        return paste(template, slots.acquire());
    }

    /** Pastes a template into an already reserved slot (a diff: blocks that already match are left alone). */
    private CompletableFuture<ArenaInstance> paste(ArenaTemplate template, int slot) {
        int ox = slots.originX(slot) - template.sizeX() / 2;
        int oz = slots.originZ(slot) - template.sizeZ() / 2;
        int oy = plugin.settings().arenaBaseY;
        ArenaInstance instance = new ArenaInstance(ids.incrementAndGet(), template, slot, world, ox, oy, oz);
        instances.add(instance);
        bySlot.put(slot, instance);
        SlotRecord rec = record(template, slot);
        if (!rec.equals(manifest.put(slot, rec))) saveManifest();
        RegionJob job = new RegionJob(plugin, world, ox, oy, oz, template.snapshot(), template.palette(), worker, true, false);
        queue.submit(job);
        return job.future().handle((result, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Pasting arena " + template.name() + " failed", error);
                instances.remove(instance);
                bySlot.remove(slot, instance);
                instance.state(ArenaInstance.State.DEAD);
                if (!(error instanceof java.util.concurrent.CancellationException)) destroySlot(slot, template);
                throw new IllegalStateException("arena paste failed", error);
            }
            pastes++;
            paintBiome(instance);
            verbose((result.blocksChanged() == 0 ? "reused " : "pasted ") + template.name() + " #" + instance.id() + " slot " + slot + " (" + result.blocksChanged()
                + " blocks, " + result.passes() + " passes, " + result.millis() + " ms: chunks " + result.loadMillis()
                + " ms, diff " + result.diffMillis() + " ms, " + result.ticks() + " ticks)");
            return instance;
        });
    }

    /**
     * Sets the template's biome over every chunk column the instance occupies (in 4×4×4 biome cells, from a little
     * below the floor to well above the ceiling). Runs right after the paste, before anyone is sent there, so the
     * chunks go out to players with the right grass, foliage and sky colours.
     */
    private void paintBiome(ArenaInstance instance) {
        org.bukkit.block.Biome biome = instance.template().biome();
        if (biome == null) return;
        ArenaTemplate t = instance.template();
        int x0 = (instance.originX() >> 4) << 4;
        int z0 = (instance.originZ() >> 4) << 4;
        int x1 = (((instance.originX() + t.sizeX() - 1) >> 4) << 4) + 15;
        int z1 = (((instance.originZ() + t.sizeZ() - 1) >> 4) << 4) + 15;
        int y0 = Math.max(world.getMinHeight(), instance.originY() - 32);
        int y1 = Math.min(world.getMaxHeight() - 1, instance.originY() + t.sizeY() + 64);
        for (int x = x0; x <= x1; x += 4) {
            for (int z = z0; z <= z1; z += 4) {
                for (int y = y0; y <= y1; y += 4) world.setBiome(x, y, z, biome);
            }
        }
    }

    /** Resets an in-use instance between rounds. The instance stays assigned to its match. */
    public CompletableFuture<RegionJob.Result> resetForNextRound(ArenaInstance instance) {
        instance.clearPlaced();
        RegionJob job = new RegionJob(plugin, world, instance.originX(), instance.originY(), instance.originZ(),
            instance.template().snapshot(), instance.template().palette(), worker, false, false);
        queue.submit(job);
        return job.future().whenComplete((r, e) -> {
            if (r != null) {
                resets++;
                if (!r.clean()) dirtyResets++;
            }
        });
    }

    /** Returns an instance after its match. It is reset and pooled, handed to a waiting match, or destroyed. */
    public void release(ArenaInstance instance) {
        if (instance.state() == ArenaInstance.State.DEAD || instance.state() == ArenaInstance.State.CLEARING) return;
        instance.state(ArenaInstance.State.RESETTING);
        instance.clearPlaced();
        RegionJob job = new RegionJob(plugin, world, instance.originX(), instance.originY(), instance.originZ(),
            instance.template().snapshot(), instance.template().palette(), worker, false, false);
        queue.submit(job);
        job.future().whenComplete((result, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Arena reset failed, destroying instance", error);
                destroy(instance);
                return;
            }
            resets++;
            if (!result.clean()) dirtyResets++;
            verbose("reset " + instance.template().name() + " #" + instance.id() + " (" + result.blocksChanged()
                + " blocks, " + result.entitiesRemoved() + " entities, " + result.passes() + " passes, "
                + result.millis() + " ms, clean=" + result.clean() + ")");
            ArenaTemplate current = templates.get(instance.template().name());
            if (current != instance.template()) {
                destroy(instance);
                servePending();
                return;
            }
            if (handToWaiting(instance)) return;
            Deque<ArenaInstance> pool = idleOf(instance.template());
            if (pool.size() < plugin.settings().keepIdlePerTemplate) {
                instance.state(ArenaInstance.State.READY);
                pool.addLast(instance);
            } else {
                destroy(instance);
            }
            servePending();
        });
    }

    private boolean handToWaiting(ArenaInstance instance) {
        for (Iterator<Pending> it = waiting.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.future().isDone()) {
                it.remove();
                continue;
            }
            if (instance.template().matches(p.tags()) || candidates(p.tags()).contains(instance.template())) {
                it.remove();
                instance.state(ArenaInstance.State.IN_USE);
                instance.markUsed();
                p.future().complete(instance);
                return true;
            }
        }
        return false;
    }

    /** Starts pastes for waiting matches while capacity allows. */
    private void servePending() {
        while (!waiting.isEmpty() && liveInstances() < plugin.settings().maxInstances) {
            Pending p = waiting.pollFirst();
            if (p.future().isDone()) continue;
            List<ArenaTemplate> candidates = candidates(p.tags());
            if (candidates.isEmpty()) {
                p.future().completeExceptionally(new IllegalStateException("no arenas configured"));
                continue;
            }
            ArenaTemplate t = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            create(t).whenComplete((instance, error) -> {
                if (error != null) p.future().completeExceptionally(error);
                else {
                    instance.state(ArenaInstance.State.IN_USE);
                    instance.markUsed();
                    if (!p.future().complete(instance)) release(instance);
                }
            });
        }
    }

    /** Clears the slot back to air, drops chunk tickets and frees the slot. */
    private void destroy(ArenaInstance instance) {
        instance.state(ArenaInstance.State.CLEARING);
        ArenaTemplate t = instance.template();
        ArenaSnapshot empty = ArenaSnapshot.empty(t.sizeX(), t.sizeY(), t.sizeZ());
        BlockData[] air = {Bukkit.createBlockData(org.bukkit.Material.AIR)};
        RegionJob job = new RegionJob(plugin, world, instance.originX(), instance.originY(), instance.originZ(), empty,
            air, worker, false, true);
        queue.submit(job);
        job.future().whenComplete((r, e) -> {
            instance.state(ArenaInstance.State.DEAD);
            instances.remove(instance);
            bySlot.remove(instance.slot(), instance);
            if (e != null) return; // cancelled at shutdown: the slot stays in the manifest and is handled next start
            manifest.remove(instance.slot());
            slots.release(instance.slot());
            saveManifest();
            clears++;
            verbose("cleared instance #" + instance.id() + " of " + t.name());
            servePending();
        });
    }

    /** After a failed paste: clear whatever was written, then free the slot. */
    private void destroySlot(int slot, ArenaTemplate template) {
        SlotRecord rec = manifest.get(slot);
        clearSlot(slot, rec != null ? rec : record(template, slot));
    }

    private Deque<ArenaInstance> idleOf(ArenaTemplate t) {
        return idle.computeIfAbsent(t.name(), k -> new ArrayDeque<>());
    }

    /** The instance whose slot contains this block column (O(1)). */
    public @Nullable ArenaInstance instanceAt(int x, int z) {
        int slot = slots.slotAt(x, z);
        return slot < 0 ? null : bySlot.get(slot);
    }

    /** Instances that hold (or are about to hold) a slot. */
    public int liveInstances() {
        int n = 0;
        for (ArenaInstance i : instances) if (i.state() != ArenaInstance.State.DEAD) n++;
        return n;
    }

    public List<ArenaInstance> instances() {
        return List.copyOf(instances);
    }

    public int idleCount() {
        int n = 0;
        for (Deque<ArenaInstance> d : idle.values()) n += d.size();
        return n;
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int slotsInUse() {
        return slots.inUse();
    }

    /** Instances re-adopted from the previous run's arena world. */
    public long restored() {
        return restored;
    }

    /** Chunks generated (or confirmed) by the background pre-generation. */
    public long pregeneratedChunks() {
        return pregenChunks;
    }

    public int pregeneratedSlots() {
        return pregenSlots;
    }

    public long pastes() {
        return pastes;
    }

    public long resets() {
        return resets;
    }

    public long clears() {
        return clears;
    }

    /** Resets whose last pass still found differences (should stay 0). */
    public long dirtyResets() {
        return dirtyResets;
    }

    public java.util.concurrent.Executor worker() {
        return worker;
    }

    private void verbose(String msg) {
        if (plugin.settings().verbose) plugin.getLogger().info("[arena] " + msg);
    }
}
