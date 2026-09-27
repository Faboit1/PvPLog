package top.cheesesmp.duelcore.arena;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The deep ground of the built-in maps: deepslate in the lower half and a bumpy, sealed bedrock floor. */
class ArenaGeneratorDepthTest {

    private static String at(ArenaSnapshot s, int x, int y, int z) {
        return s.palette()[s.getAt((y * s.sizeZ() + z) * s.sizeX() + x)];
    }

    @Test
    void bedrockProfileIsSolidAtTheBottomAndThinsOut() {
        int n = 20_000;
        double last = 1;
        for (int y = 0; y < ArenaGenerator.BEDROCK_LAYERS + 3; y++) {
            int hits = 0;
            for (int i = 0; i < n; i++) {
                boolean b = ArenaGenerator.bedrock(11, i % 180, y, i / 180);
                assertEquals(b, ArenaGenerator.bedrock(11, i % 180, y, i / 180), "deterministic");
                if (b) hits++;
            }
            double share = hits / (double) n;
            double expected = y >= ArenaGenerator.BEDROCK_LAYERS ? 0
                : (ArenaGenerator.BEDROCK_LAYERS - y) / (double) ArenaGenerator.BEDROCK_LAYERS;
            assertEquals(expected, share, 0.02, "bedrock share at y=" + y);
            assertTrue(share <= last, "sparser upwards at y=" + y);
            last = share;
        }
    }

    @Test
    void deepslateBlendsIntoStoneOverTheBand() {
        int top = ArenaGenerator.DEEPSLATE;
        int band = ArenaGenerator.DEEPSLATE_BAND;
        double last = 1;
        for (int y = top - band - 2; y < top + 2; y++) {
            int hits = 0;
            for (int i = 0; i < 10_000; i++) if (ArenaGenerator.deepslate(23, i % 100, y, i / 100)) hits++;
            double share = hits / 10_000.0;
            if (y < top - band) assertEquals(1.0, share, "all deepslate at y=" + y);
            else if (y >= top) assertEquals(0.0, share, "no deepslate at y=" + y);
            else assertTrue(share > 0.05 && share < 0.95, "mixed at y=" + y + ": " + share);
            assertTrue(share <= last, "less deepslate upwards at y=" + y);
            last = share;
        }
    }

    @Test
    void groundIsTwiceAsDeepWithDeepslateAndABumpyFloor() {
        int ring = ArenaGenerator.RING;
        for (ArenaGenerator.Generated g : ArenaGenerator.defaults()) {
            ArenaSnapshot s = g.snapshot();
            assertEquals(ArenaGenerator.HEIGHT, s.sizeY());
            assertTrue(s.hasBedrockFloor(), g.name() + " bottom layer");
            int bumps = 0;
            int inner = 0;
            int deep = 0;
            int deepCells = 0;
            for (int x = ring; x < s.sizeX() - ring; x++) {
                for (int z = ring; z < s.sizeZ() - ring; z++) {
                    inner++;
                    if (at(s, x, 1, z).equals("minecraft:bedrock")) bumps++;
                    for (int y = ArenaGenerator.BEDROCK_LAYERS; y < s.sizeY(); y++) {
                        assertFalse(at(s, x, y, z).equals("minecraft:bedrock"), g.name() + " bedrock too high");
                    }
                    for (int y = ArenaGenerator.BEDROCK_LAYERS; y < ArenaGenerator.DEEPSLATE - ArenaGenerator.DEEPSLATE_BAND; y++) {
                        deepCells++;
                        String b = at(s, x, y, z);
                        if (b.startsWith("minecraft:deepslate") || b.equals("minecraft:tuff")) deep++;
                    }
                }
            }
            // y = 1 is bedrock in about 80 % of the columns: neither flat nor missing
            assertTrue(bumps > inner * 0.7 && bumps < inner * 0.9, g.name() + " bumps " + bumps + "/" + inner);
            assertEquals(deepCells, deep, g.name() + " lower half is deepslate");
        }
    }

    @Test
    void generationIsDeterministic() throws IOException {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ArenaGenerator.generate("mesa").snapshot().write(a);
        ArenaGenerator.generate("mesa").snapshot().write(b);
        assertArrayEquals(a.toByteArray(), b.toByteArray());
    }
}
