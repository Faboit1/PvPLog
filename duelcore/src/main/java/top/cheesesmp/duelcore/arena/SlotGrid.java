package top.cheesesmp.duelcore.arena;

import java.util.BitSet;

/**
 * Hands out instance slots in the arena world. Slot i sits at (col × spacing, row × spacing) on a square-ish grid,
 * so no two instances can ever see or reach each other. The lowest free slot is reused first, which keeps the
 * set of touched chunks small.
 */
public final class SlotGrid {

    private static final int COLUMNS = 16;

    private final int spacing;
    private final BitSet used = new BitSet();

    public SlotGrid(int spacing) {
        this.spacing = spacing;
    }

    public int acquire() {
        int slot = used.nextClearBit(0);
        used.set(slot);
        return slot;
    }

    /** Marks a specific slot as used (restoring instances from the manifest). */
    public void reserve(int slot) {
        used.set(slot);
    }

    public boolean isUsed(int slot) {
        return used.get(slot);
    }

    public void release(int slot) {
        used.clear(slot);
    }

    public int inUse() {
        return used.cardinality();
    }

    public int originX(int slot) {
        return (slot % COLUMNS) * spacing + spacing / 2;
    }

    public int originZ(int slot) {
        return (slot / COLUMNS) * spacing + spacing / 2;
    }

    /** Slot whose cell contains the block column (x, z), or -1 outside the grid. */
    public int slotAt(int x, int z) {
        if (x < 0 || z < 0) return -1;
        int col = x / spacing;
        int row = z / spacing;
        if (col >= COLUMNS) return -1;
        return row * COLUMNS + col;
    }
}
