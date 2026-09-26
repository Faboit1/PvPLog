package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.arena.ArenaGenerator;
import top.cheesesmp.duelcore.arena.ArenaSnapshot;
import top.cheesesmp.duelcore.arena.RelPos;

class ArenaGeneratorTest {

    private static String at(ArenaSnapshot s, int x, int y, int z) {
        return s.palette()[s.getAt((y * s.sizeZ() + z) * s.sizeX() + x)];
    }

    private static boolean passable(String state) {
        return state.equals("minecraft:air") || state.startsWith("minecraft:short_grass") || state.startsWith("minecraft:snow")
            || state.startsWith("minecraft:fern")
            || state.startsWith("minecraft:leaf_litter") || state.startsWith("minecraft:dead_bush")
            || state.startsWith("minecraft:short_dry_grass") || state.contains("dandelion") || state.contains("poppy")
            || state.contains("daisy") || state.contains("cornflower") || state.contains("bluet");
    }

    @Test
    void mapsAreFencedPlayableAndDistinct() throws IOException {
        Set<String> names = new HashSet<>();
        Set<String> biomes = new HashSet<>();
        for (ArenaGenerator.Generated g : ArenaGenerator.defaults()) {
            ArenaSnapshot s = g.snapshot();
            assertTrue(names.add(g.name()), "duplicate map " + g.name());
            assertTrue(biomes.add(g.biome()), "biome used twice: " + g.biome());
            for (RelPos p : new RelPos[] {g.spawn1(), g.spawn2()}) {
                int x = (int) Math.floor(p.x());
                int y = (int) Math.floor(p.y());
                int z = (int) Math.floor(p.z());
                for (int dx = -1; dx <= 0; dx++) {
                    String ground = at(s, x + dx, y - 1, z);
                    assertFalse(passable(ground) || ground.contains("barrier"), g.name() + " spawn ground " + ground);
                    assertTrue(passable(at(s, x + dx, y, z)), g.name() + " spawn feet " + at(s, x + dx, y, z));
                    assertTrue(passable(at(s, x + dx, y + 1, z)), g.name() + " spawn head " + at(s, x + dx, y + 1, z));
                }
            }
            // ceiling and every wall column is barrier from the ground up
            int top = s.sizeY() - 1;
            for (int x = 0; x < s.sizeX(); x++) {
                for (int z = 0; z < s.sizeZ(); z++) {
                    assertEquals("minecraft:barrier", at(s, x, top, z), g.name() + " ceiling");
                    boolean edge = x == 0 || z == 0 || x == s.sizeX() - 1 || z == s.sizeZ() - 1;
                    if (edge) assertEquals("minecraft:barrier", at(s, x, top - 1, z), g.name() + " wall");
                }
            }
            // the snapshot stays small on disk (run-length encoded)
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            s.write(out);
            assertTrue(out.size() < 1_000_000, g.name() + " is " + out.size() + " bytes");
            assertTrue(Math.abs(g.spawn1().y() - g.spawn2().y()) <= 2, g.name() + " spawns at very different heights");
        }
        assertTrue(names.size() >= 6);
        assertFalse(names.contains("blossom"), "blossom was removed");
    }

    /**
     * Nobody digs or towers out: the 3 outer columns on every side are barrier from just above the bedrock to the
     * top, except the surface block (and a plant on it) of the inner two, and the top 3 layers are barrier everywhere.
     */
    @Test
    void barrierRingIsThreeThickAndSealed() {
        int ring = 3;
        for (ArenaGenerator.Generated g : ArenaGenerator.defaults()) {
            ArenaSnapshot s = g.snapshot();
            int sx = s.sizeX();
            int sy = s.sizeY();
            int sz = s.sizeZ();
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    assertEquals("minecraft:bedrock", at(s, x, 0, z), g.name() + " floor at " + x + "," + z);
                    for (int y = sy - ring; y < sy; y++) {
                        assertEquals("minecraft:barrier", at(s, x, y, z), g.name() + " ceiling at " + x + "," + y + "," + z);
                    }
                    boolean edge = x < ring || z < ring || x >= sx - ring || z >= sz - ring;
                    if (!edge) continue;
                    String where = g.name() + " ring at " + x + "," + z;
                    if (x == 0 || z == 0 || x == sx - 1 || z == sz - 1) {
                        // the outermost column is solid: mining the kept surface blocks never opens a tunnel out
                        for (int y = 1; y < sy; y++) assertEquals("minecraft:barrier", at(s, x, y, z), where + " y=" + y);
                        continue;
                    }
                    // the lowest non-barrier block is the surface: everything below it is barrier
                    int surface = 1;
                    while (surface < sy && at(s, x, surface, z).equals("minecraft:barrier")) surface++;
                    assertTrue(surface >= 10 && surface < sy - ring, where + ": no surface block (" + surface + ")");
                    String top = at(s, x, surface, z);
                    assertFalse(passable(top), where + ": surface is " + top);
                    assertFalse(top.contains("log") || top.contains("leaves") || top.contains("cactus"), where + ": tree " + top);
                    int above = surface + 1;
                    if (!at(s, x, above, z).equals("minecraft:barrier")) {
                        String deco = at(s, x, above, z);
                        assertTrue(passable(deco) && !deco.equals("minecraft:air"), where + ": above the surface " + deco);
                        above++;
                    }
                    for (int y = above; y < sy; y++) {
                        assertEquals("minecraft:barrier", at(s, x, y, z), where + " y=" + y);
                    }
                }
            }
        }
    }

    @Test
    void deepGroundAndTheSameRoomAbove() {
        for (ArenaGenerator.Generated g : ArenaGenerator.defaults()) {
            ArenaSnapshot s = g.snapshot();
            double spawn = Math.min(g.spawn1().y(), g.spawn2().y());
            assertTrue(spawn >= 25, g.name() + " only " + spawn + " blocks of ground under the spawn");
            assertTrue(s.sizeY() - 3 - spawn >= 38, g.name() + " too little air above the spawn");
            assertTrue(spawn + g.buildHeight() < s.sizeY() - 3, g.name() + " build limit inside the ceiling");
        }
    }
}
