package top.cheesesmp.duelcore.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The settings bit field: layout, the relative bits (no migration) and the three-way duel request choice. */
class SettingTest {

    @Test
    void bitsAreUniqueAndFitAnInt() {
        Set<Integer> bits = new HashSet<>();
        for (Setting s : Setting.values()) {
            assertTrue(s.bit() >= 0 && s.bit() < 32, s + " bit " + s.bit());
            assertTrue(bits.add(s.bit()), s + " reuses bit " + s.bit());
        }
    }

    @Test
    void oldBitsKeepTheirMeaning() {
        // rows written before the relative bits: bits 0-9 hold the values, the backfills of v5/v6 rely on it
        assertEquals(943, Setting.defaults(), "a new row stores exactly what it stored before");
        for (Setting s : Setting.values()) assertEquals(s.bit() >= Setting.RELATIVE_FROM, s.relative(), s.name());
        assertTrue(Setting.SOUNDS.read(Setting.SOUNDS.mask()));
        assertFalse(Setting.SOUNDS.read(0));
    }

    @Test
    void newSettingsNeedNoMigration() {
        // an existing row (any of bits 0-9, none of the newer ones) reads every newer setting as its default
        for (int old : List.of(0, 943, 1023, 5)) {
            for (Setting s : Setting.values()) {
                if (s.relative()) assertEquals(s.defaultValue(), s.read(old), s + " in " + old);
            }
        }
        for (Setting s : Setting.values()) assertEquals(s.defaultValue(), s.read(Setting.defaults()), s.name());
    }

    @Test
    void countryAndMatchSettingsUseBits25To30() {
        // bits 22-24 belong to the country flag settings; 31 (the sign bit) is MATCH_MUSIC
        List<Setting> added = List.of(Setting.PREFER_COUNTRY, Setting.DEATH_MESSAGES, Setting.MENU_SOUNDS,
            Setting.RESPAWN_ANIMATIONS, Setting.ROUND_BANNERS, Setting.SPECTATOR_ALERTS);
        for (int i = 0; i < added.size(); i++) {
            Setting s = added.get(i);
            assertEquals(25 + i, s.bit(), s.name());
            assertTrue(s.relative(), s + " needs no migration");
            assertTrue(s.defaultValue(), s + " is on by default (what players had before it existed)");
        }
        // every setting away from its default fits the (signed) INT column and reads back (bit 31 = MATCH_MUSIC)
        int changed = 0;
        for (Setting s : Setting.values()) changed = s.write(changed, !s.defaultValue());
        for (Setting s : Setting.values()) assertEquals(!s.defaultValue(), s.read(changed), s.name());
        for (Setting s : Setting.values()) assertEquals(!s.defaultValue(), s.read(changed), s.name());
    }

    @Test
    void writeThenReadRoundTrips() {
        for (Setting s : Setting.values()) {
            for (boolean v : new boolean[] {true, false}) {
                int bits = s.write(Setting.defaults(), v);
                assertEquals(v, s.read(bits), s + " = " + v);
                for (Setting other : Setting.values()) {
                    if (other != s) assertEquals(other.defaultValue(), other.read(bits), other + " untouched by " + s);
                }
            }
        }
    }

    @Test
    void duelRequestsHaveThreeStates() {
        PlayerProfile p = new PlayerProfile(1, UUID.randomUUID(), "P", Setting.defaults(), null, null, 0, Map.of());
        assertEquals(DuelRequests.EVERYONE, p.duelRequests());
        for (DuelRequests who : DuelRequests.values()) {
            p.duelRequests(who);
            assertEquals(who, p.duelRequests());
        }
        // a row from before the friends-only bit: on = everyone, off = nobody
        assertEquals(DuelRequests.NOBODY, new PlayerProfile(1, UUID.randomUUID(), "Q", 0, null, null, 0, Map.of()).duelRequests());
        assertEquals(DuelRequests.NOBODY, DuelRequests.of(false, true));
        assertTrue(DuelRequests.EVERYONE.allows(false));
        assertTrue(DuelRequests.FRIENDS.allows(true));
        assertFalse(DuelRequests.FRIENDS.allows(false));
        assertFalse(DuelRequests.NOBODY.allows(true));
        assertEquals(DuelRequests.FRIENDS, DuelRequests.EVERYONE.next());
        assertEquals(DuelRequests.NOBODY, DuelRequests.FRIENDS.next());
        assertEquals(DuelRequests.EVERYONE, DuelRequests.NOBODY.next());
    }
}
