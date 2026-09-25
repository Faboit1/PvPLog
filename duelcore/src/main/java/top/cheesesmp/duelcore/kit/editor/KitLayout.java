package top.cheesesmp.duelcore.kit.editor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;

/**
 * The maths of a personal kit layout, without Bukkit (unit tested).
 *
 * <p>A kit's items sit in 37 <em>positions</em>: 0–35 are the player inventory slots (0–8 the hotbar) and 36 is the
 * offhand. A layout is an {@code int[37]} that says, for every position, which position of the kit's default loadout
 * (its <em>source</em>) goes there, or {@link #EMPTY}. It is only valid when it is a complete permutation: every
 * source the kit fills appears exactly once, nothing else appears, and nothing appears twice. Armour is never part of
 * a layout.
 *
 * <p>The kit editor shows the positions in a 6-row chest ({@link #editorSlot(int)}): rows 1–3 are inventory slots
 * 9–35, row 4 the hotbar, and slot 40 (row 5) the offhand, so it looks like the real inventory.
 */
public final class KitLayout {

    /** Editable positions: 36 inventory slots + the offhand. */
    public static final int SIZE = 37;
    public static final int OFFHAND = 36;
    /** A position the layout leaves empty. */
    public static final int EMPTY = -1;
    /** Chest slot of the offhand position in the editor (row 5). */
    public static final int EDITOR_OFFHAND = 40;

    private KitLayout() {
    }

    // ------------------------------------------------------------------ positions ↔ editor slots

    /** The editor chest slot showing {@code position} (0–36). */
    public static int editorSlot(int position) {
        if (position < 0 || position >= SIZE) throw new IllegalArgumentException("position " + position);
        if (position == OFFHAND) return EDITOR_OFFHAND;
        return position < 9 ? 27 + position : position - 9;
    }

    /** The position shown in editor chest slot {@code slot}, or -1 when that slot isn't editable. */
    public static int position(int slot) {
        if (slot >= 0 && slot < 27) return slot + 9;
        if (slot >= 27 && slot < 36) return slot - 27;
        if (slot == EDITOR_OFFHAND) return OFFHAND;
        return -1;
    }

    // ------------------------------------------------------------------ layouts

    /** The default layout of a kit: every source stays where it is. {@code filled[i]} = the kit has an item at i. */
    public static int[] identity(boolean[] filled) {
        int[] map = new int[SIZE];
        for (int i = 0; i < SIZE; i++) map[i] = i < filled.length && filled[i] ? i : EMPTY;
        return map;
    }

    /** True when {@code map} places every filled source exactly once and nothing else. */
    public static boolean valid(int @Nullable [] map, boolean[] filled) {
        if (map == null || map.length != SIZE || filled.length != SIZE) return false;
        boolean[] seen = new boolean[SIZE];
        for (int source : map) {
            if (source == EMPTY) continue;
            if (source < 0 || source >= SIZE || !filled[source] || seen[source]) return false;
            seen[source] = true;
        }
        for (int i = 0; i < SIZE; i++) if (filled[i] && !seen[i]) return false;
        return true;
    }

    /** True when the layout is the kit's default (nothing moved). */
    public static boolean isIdentity(int[] map, boolean[] filled) {
        return Arrays.equals(map, identity(filled));
    }

    /**
     * Lays out {@code sources} (the kit's items by source position, length 37) with {@code map}. The result has the
     * item for every position; the source objects themselves are placed (callers pass copies).
     */
    public static <T> @Nullable T[] arrange(@Nullable T[] sources, int[] map) {
        if (sources.length != SIZE || map.length != SIZE) throw new IllegalArgumentException("need " + SIZE + " positions");
        @Nullable T[] out = Arrays.copyOf(sources, SIZE);
        Arrays.fill(out, null);
        for (int pos = 0; pos < SIZE; pos++) {
            int source = map[pos];
            if (source != EMPTY) out[pos] = sources[source];
        }
        return out;
    }

    // ------------------------------------------------------------------ storage

    /** "0,1,-1,…" (37 numbers), the dc_kit_layouts.layout column. */
    public static String encode(int[] map) {
        StringBuilder sb = new StringBuilder(SIZE * 3);
        for (int i = 0; i < map.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(map[i]);
        }
        return sb.toString();
    }

    /** Parses {@link #encode(int[])}; null when the text isn't 37 numbers in range (a broken row is ignored). */
    public static int @Nullable [] decode(@Nullable String text) {
        if (text == null) return null;
        String[] parts = text.split(",", -1);
        if (parts.length != SIZE) return null;
        int[] map = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            try {
                map[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (map[i] < EMPTY || map[i] >= SIZE) return null;
        }
        return map;
    }

    // ------------------------------------------------------------------ kit fingerprint

    /** {@link #part(String, int, String)} of an item without components. */
    public static String part(@Nullable String typeKey, int amount) {
        return part(typeKey, amount, null);
    }

    /**
     * One source position's part of the kit fingerprint: item type, amount and components
     * ({@code "minecraft:splash_potion*1[minecraft:potion_contents={potion:\"minecraft:healing\"}]"}), or "" when the
     * kit leaves it empty. The components count so that two items of the same type (a Healing and a Strength potion,
     * two differently enchanted swords) swapped in the kit file reset the layouts instead of silently handing players
     * the other item in their slot; adding, removing, moving, re-counting or changing items resets them too. An item
     * without components ({@code components} null, empty or "[]") gives just {@code "type*amount"}.
     */
    public static String part(@Nullable String typeKey, int amount, @Nullable String components) {
        if (typeKey == null || amount <= 0) return "";
        String base = typeKey + "*" + amount;
        return components == null || components.isEmpty() || "[]".equals(components) ? base : base + components;
    }

    /**
     * The kit fingerprint stored with a layout (dc_kit_layouts.kit_hash): a CRC-32 of the 37 {@link #part}s. It is
     * stable across restarts and Java versions (no {@code hashCode} of objects involved).
     */
    public static int hash(String[] parts) {
        if (parts.length != SIZE) throw new IllegalArgumentException("need " + SIZE + " parts");
        CRC32 crc = new CRC32();
        for (int i = 0; i < SIZE; i++) {
            crc.update((i + "=" + parts[i] + ";").getBytes(StandardCharsets.UTF_8));
        }
        return (int) crc.getValue();
    }

    /** Which source positions a fingerprint fills. */
    public static boolean[] filled(String[] parts) {
        boolean[] filled = new boolean[SIZE];
        for (int i = 0; i < SIZE && i < parts.length; i++) filled[i] = !parts[i].isEmpty();
        return filled;
    }
}
