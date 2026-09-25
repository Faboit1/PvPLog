package top.cheesesmp.duelcore.kit.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KitLayoutTest {

    /** A sword kit: sword in 0, pearls in 1, gapples in 8, arrows in 20, a shield in the offhand. */
    private static String[] kit() {
        String[] parts = new String[KitLayout.SIZE];
        java.util.Arrays.fill(parts, "");
        parts[0] = KitLayout.part("minecraft:diamond_sword", 1);
        parts[1] = KitLayout.part("minecraft:ender_pearl", 16);
        parts[8] = KitLayout.part("minecraft:golden_apple", 64);
        parts[20] = KitLayout.part("minecraft:arrow", 64);
        parts[KitLayout.OFFHAND] = KitLayout.part("minecraft:shield", 1);
        return parts;
    }

    @Test
    void editorSlotsLookLikeTheInventory() {
        assertEquals(27, KitLayout.editorSlot(0), "hotbar slot 0 is row 4");
        assertEquals(35, KitLayout.editorSlot(8));
        assertEquals(0, KitLayout.editorSlot(9), "inventory slot 9 is the top left");
        assertEquals(26, KitLayout.editorSlot(35));
        assertEquals(KitLayout.EDITOR_OFFHAND, KitLayout.editorSlot(KitLayout.OFFHAND));
        Set<Integer> slots = new HashSet<>();
        for (int pos = 0; pos < KitLayout.SIZE; pos++) {
            int slot = KitLayout.editorSlot(pos);
            assertTrue(slots.add(slot), "each position has its own slot");
            assertEquals(pos, KitLayout.position(slot), "round trip of " + pos);
        }
        for (int slot = 0; slot < 54; slot++) {
            if (!slots.contains(slot)) assertEquals(-1, KitLayout.position(slot), "slot " + slot + " is not editable");
        }
        assertEquals(-1, KitLayout.position(-999));
    }

    @Test
    void identityIsValidAndDefault() {
        boolean[] filled = KitLayout.filled(kit());
        int[] id = KitLayout.identity(filled);
        assertEquals(0, id[0]);
        assertEquals(KitLayout.EMPTY, id[2]);
        assertEquals(KitLayout.OFFHAND, id[KitLayout.OFFHAND]);
        assertTrue(KitLayout.valid(id, filled));
        assertTrue(KitLayout.isIdentity(id, filled));
    }

    @Test
    void permutationsAreValid() {
        boolean[] filled = KitLayout.filled(kit());
        int[] map = KitLayout.identity(filled);
        // swap sword and pearls, move the gapples into slot 30, the shield into hotbar 5 and the arrows to the offhand
        map[0] = 1;
        map[1] = 0;
        map[8] = KitLayout.EMPTY;
        map[30] = 8;
        map[5] = KitLayout.OFFHAND;
        map[KitLayout.OFFHAND] = 20;
        map[20] = KitLayout.EMPTY;
        assertTrue(KitLayout.valid(map, filled));
        assertFalse(KitLayout.isIdentity(map, filled));
    }

    @Test
    void incompleteOrDuplicatedLayoutsAreRejected() {
        boolean[] filled = KitLayout.filled(kit());
        int[] missing = KitLayout.identity(filled);
        missing[8] = KitLayout.EMPTY; // the gapples are gone
        assertFalse(KitLayout.valid(missing, filled));

        int[] twice = KitLayout.identity(filled);
        twice[3] = 0; // the sword twice
        assertFalse(KitLayout.valid(twice, filled));

        int[] foreign = KitLayout.identity(filled);
        foreign[3] = 2; // position 2 of the kit is empty: nothing to place
        assertFalse(KitLayout.valid(foreign, filled));

        int[] outOfRange = KitLayout.identity(filled);
        outOfRange[3] = 37;
        assertFalse(KitLayout.valid(outOfRange, filled));
        outOfRange[3] = -2;
        assertFalse(KitLayout.valid(outOfRange, filled));

        assertFalse(KitLayout.valid(new int[36], filled), "wrong length");
        assertFalse(KitLayout.valid(null, filled));
    }

    @Test
    void emptyKitOnlyAcceptsTheEmptyLayout() {
        boolean[] none = new boolean[KitLayout.SIZE];
        int[] empty = KitLayout.identity(none);
        for (int v : empty) assertEquals(KitLayout.EMPTY, v);
        assertTrue(KitLayout.valid(empty, none));
        empty[0] = 0;
        assertFalse(KitLayout.valid(empty, none));
    }

    @Test
    void arrangePlacesEverySourceOnce() {
        String[] sources = new String[KitLayout.SIZE];
        sources[0] = "sword";
        sources[1] = "pearls";
        sources[KitLayout.OFFHAND] = "shield";
        boolean[] filled = new boolean[KitLayout.SIZE];
        filled[0] = filled[1] = filled[KitLayout.OFFHAND] = true;
        int[] map = KitLayout.identity(filled);
        map[0] = KitLayout.OFFHAND; // shield in hotbar 0
        map[KitLayout.OFFHAND] = 0; // sword in the offhand
        map[1] = KitLayout.EMPTY;
        map[17] = 1; // pearls in the inventory
        assertTrue(KitLayout.valid(map, filled));
        String[] out = KitLayout.arrange(sources, map);
        assertEquals("shield", out[0]);
        assertNull(out[1]);
        assertEquals("pearls", out[17]);
        assertEquals("sword", out[KitLayout.OFFHAND]);
        long placed = java.util.Arrays.stream(out).filter(java.util.Objects::nonNull).count();
        assertEquals(3, placed);
        assertArrayEquals(sources, KitLayout.arrange(sources, KitLayout.identity(filled)), "identity changes nothing");
    }

    @Test
    void encodeDecodeRoundTrip() {
        boolean[] filled = KitLayout.filled(kit());
        int[] map = KitLayout.identity(filled);
        map[0] = 1;
        map[1] = 0;
        String text = KitLayout.encode(map);
        assertTrue(text.length() <= 255, "fits the column");
        assertArrayEquals(map, KitLayout.decode(text));
        assertNull(KitLayout.decode(null));
        assertNull(KitLayout.decode(""));
        assertNull(KitLayout.decode("1,2,3"), "wrong length");
        assertNull(KitLayout.decode(text.replaceFirst("1", "x")), "not a number");
        assertNull(KitLayout.decode(text.replaceFirst("^1", "40")), "out of range");
    }

    @Test
    void kitHashIsStable() {
        // a stored fingerprint must mean the same thing after a restart or an update: a fixed value, not hashCode()
        assertEquals(EXPECTED_HASH, KitLayout.hash(kit()));
        assertEquals(KitLayout.hash(kit()), KitLayout.hash(kit()));
    }

    @Test
    void kitHashFollowsTypesAndAmountsPerSlot() {
        int base = KitLayout.hash(kit());
        String[] amount = kit();
        amount[1] = KitLayout.part("minecraft:ender_pearl", 8);
        assertNotEquals(base, KitLayout.hash(amount), "amount changed");
        String[] type = kit();
        type[0] = KitLayout.part("minecraft:netherite_sword", 1);
        assertNotEquals(base, KitLayout.hash(type), "type changed");
        String[] moved = kit();
        moved[2] = moved[1];
        moved[1] = "";
        assertNotEquals(base, KitLayout.hash(moved), "item moved in the kit file");
        String[] added = kit();
        added[35] = KitLayout.part("minecraft:cobweb", 8);
        assertNotEquals(base, KitLayout.hash(added), "item added");
        assertEquals("", KitLayout.part(null, 1));
        assertEquals("", KitLayout.part("minecraft:stone", 0));
    }

    /** CRC-32 of the {@link #kit()} fingerprint. */
    private static final int EXPECTED_HASH = 11987521;
}
