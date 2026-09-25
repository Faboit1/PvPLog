package top.cheesesmp.duelcore.arena;

import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.jspecify.annotations.Nullable;

/**
 * An arena design: block snapshot + spawns + tags. Instances are pasted copies of it.
 * The palette is resolved to {@link BlockData} once (main thread) and shared by every reset job.
 */
public final class ArenaTemplate {

    private final String name;
    private final String displayName;
    private final List<String> tags;
    private final boolean enabled;
    private final RelPos spawn1;
    private final RelPos spawn2;
    private final int buildHeight;
    private final ArenaSnapshot snapshot;
    private final BlockData[] palette;
    private final String stamp;
    private @Nullable Biome biome;

    public ArenaTemplate(String name, String displayName, List<String> tags, boolean enabled, RelPos spawn1,
                         RelPos spawn2, int buildHeight, ArenaSnapshot snapshot, String stamp, List<String> errors) {
        this.stamp = stamp;
        this.name = name;
        this.displayName = displayName;
        this.tags = tags.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        this.enabled = enabled;
        this.spawn1 = spawn1;
        this.spawn2 = spawn2;
        this.buildHeight = buildHeight;
        this.snapshot = snapshot;
        String[] raw = snapshot.palette();
        this.palette = new BlockData[raw.length];
        BlockData air = Bukkit.createBlockData(org.bukkit.Material.AIR);
        for (int i = 0; i < raw.length; i++) {
            try {
                palette[i] = Bukkit.createBlockData(raw[i]);
            } catch (IllegalArgumentException e) {
                errors.add("arena " + name + ": unknown block state '" + raw[i] + "' (replaced with air)");
                palette[i] = air;
            }
        }
    }

    public String name() {
        return name;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> tags() {
        return tags;
    }

    public boolean enabled() {
        return enabled;
    }

    public RelPos spawn1() {
        return spawn1;
    }

    public RelPos spawn2() {
        return spawn2;
    }

    /** Blocks may be placed up to this many blocks above the lowest spawn. */
    public int buildHeight() {
        return buildHeight;
    }

    public ArenaSnapshot snapshot() {
        return snapshot;
    }

    public BlockData[] palette() {
        return palette;
    }

    public int sizeX() {
        return snapshot.sizeX();
    }

    public int sizeY() {
        return snapshot.sizeY();
    }

    public int sizeZ() {
        return snapshot.sizeZ();
    }

    /** Identifies the exact block content (file size + modification time), used to reuse pasted slots after restarts. */
    public String stamp() {
        return stamp;
    }

    /** Biome painted over the instance's chunks when it is pasted, or null to leave the world's biome. */
    public @Nullable Biome biome() {
        return biome;
    }

    ArenaTemplate biome(@Nullable Biome biome) {
        this.biome = biome;
        return this;
    }

    public boolean matches(List<String> kitTags) {
        for (String t : kitTags) if (tags.contains(t)) return true;
        return false;
    }
}
