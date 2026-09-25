package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SoundPoolTest {

    @Test
    void parsesKeysPitchRangesVolumesAndDelays() {
        SoundPool pool = SoundPool.parse(List.of(
            "block.beacon.activate 1.4-1.6 + minecraft:block.amethyst_block.chime 1.2 0.5 @3",
            "ui.toast.challenge_complete"));
        assertTrue(pool.problems().isEmpty(), pool.problems().toString());
        assertEquals(2, pool.combos().size());
        List<SoundPool.Note> first = pool.combos().getFirst();
        assertEquals(new SoundPool.Note("minecraft:block.beacon.activate", 1.4f, 1.6f, 0.7f, 0), first.get(0));
        assertEquals(new SoundPool.Note("minecraft:block.amethyst_block.chime", 1.2f, 1.2f, 0.5f, 3), first.get(1));
        assertEquals(new SoundPool.Note("minecraft:ui.toast.challenge_complete", 1f, 1f, 0.7f, 0), pool.combos().get(1).getFirst());
    }

    @Test
    void clampsAndSkipsBadLines() {
        SoundPool pool = SoundPool.parse(java.util.Arrays.asList("Block.Note_Block.Pling 3.0-0.1 9 @500", "not a sound!",
            "block.note_block.hat x", "", null, "block.note_block.bell 1 1 1"));
        assertEquals(1, pool.combos().size());
        assertEquals(new SoundPool.Note("minecraft:block.note_block.pling", 0.5f, 2f, 4f, 100), pool.combos().getFirst().getFirst());
        assertEquals(3, pool.problems().size(), pool.problems().toString());
        assertTrue(SoundPool.parse(List.of()).isEmpty());
        assertTrue(SoundPool.EMPTY.pick(new Random(1)).isEmpty());
    }

    @Test
    void picksVaryAndStayInRange() {
        SoundPool pool = SoundPool.parse(List.of("a.b 1.0-2.0", "c.d 0.5 + e.f 1.5 0.3 @2", "g.h"));
        Random random = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            List<SoundPool.Played> played = pool.pick(random);
            seen.add(played.getFirst().key());
            for (SoundPool.Played p : played) {
                assertTrue(p.pitch() >= 0.5f && p.pitch() <= 2f);
                if (p.key().equals("minecraft:a.b")) assertTrue(p.pitch() >= 1f && p.pitch() <= 2f);
                if (p.key().equals("minecraft:e.f")) assertEquals(2, p.delay());
            }
        }
        assertEquals(Set.of("minecraft:a.b", "minecraft:c.d", "minecraft:g.h"), seen);
    }

    /** Every default sound in config.yml parses and is a real vanilla sound (a constant of org.bukkit.Sound). */
    @Test
    @SuppressWarnings("unchecked")
    void defaultPoolsAreValidVanillaSounds() throws Exception {
        Map<String, Object> config;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            config = new Yaml().load(in);
        }
        Map<String, Object> anim = (Map<String, Object>) config.get("animations");
        // read the constant names without initialising the class (that needs a running server)
        Set<String> constants = new HashSet<>();
        for (Field f : Class.forName("org.bukkit.Sound", false, getClass().getClassLoader()).getFields()) {
            constants.add(f.getName());
        }
        for (String key : List.of("match-found-sounds", "fight-start-sounds")) {
            SoundPool pool = SoundPool.parse((List<String>) anim.get(key));
            assertTrue(pool.problems().isEmpty(), key + ": " + pool.problems());
            assertTrue(pool.combos().size() >= 3, key + " should offer variety");
            for (List<SoundPool.Note> combo : pool.combos()) {
                for (SoundPool.Note n : combo) {
                    String constant = n.key().substring("minecraft:".length()).replace('.', '_').toUpperCase(Locale.ROOT);
                    assertTrue(constants.contains(constant), key + ": unknown sound " + n.key());
                }
            }
        }
    }
}
