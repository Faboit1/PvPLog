package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.PlayerDao;
import top.cheesesmp.duelcore.profile.Setting;

/** Players who existed before FRIEND_ALERTS / PARTY_INVITES / QUEUE_MUSIC get them switched on (their default), nothing else. */
class SettingsMigrationTest {

    @TempDir
    Path dir;

    @Test
    void newDefaultOnSettingsAreBackfilled() {
        Database db = Database.sqlite(new File(dir.toFile(), "t.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            UUID uuid = UUID.randomUUID();
            // a row from before schema v5: the old defaults (bits 0-5), sounds switched off by the player
            int old = Setting.DUEL_REQUESTS.mask() | Setting.SIDEBAR.mask() | Setting.CHAT_TAGS.mask()
                | Setting.ALLOW_SPECTATORS.mask();
            PlayerDao.PlayerRow row = db.submit(c -> PlayerDao.loadOrCreate(c, uuid, "Old", 1L, old)).join();
            db.submit(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE dc_meta SET v = '4' WHERE k = 'schema_version'")) {
                    ps.executeUpdate();
                }
                return null;
            }).join();
            assertEquals(Migrations.LATEST, db.submit(c -> Migrations.migrate(c, db.dialect())).join());
            int settings = db.submit(c -> PlayerDao.findByUuid(c, uuid)).join().orElseThrow().settings();
            assertEquals(row.id(), db.submit(c -> PlayerDao.findByUuid(c, uuid)).join().orElseThrow().id());
            assertEquals(old | Setting.FRIEND_ALERTS.mask() | Setting.PARTY_INVITES.mask() | Setting.QUEUE_MUSIC.mask(), settings);
            assertTrue((settings & Setting.KEEP_QUEUING.mask()) == 0, "Keep Queuing defaults to off");
            assertTrue((settings & Setting.SOUNDS.mask()) == 0, "a setting the player turned off stays off");
        } finally {
            db.close();
        }
    }

    @Test
    void countriesPickedBeforeAutoDetectStayManual() {
        Database db = Database.sqlite(new File(dir.toFile(), "geo.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            UUID typed = UUID.randomUUID();
            UUID none = UUID.randomUUID();
            int typedId = db.submit(c -> PlayerDao.loadOrCreate(c, typed, "Typed", 1L, Setting.defaults())).join().id();
            db.submit(c -> PlayerDao.loadOrCreate(c, none, "None", 1L, Setting.defaults())).join();
            int soundsOff = Setting.SOUNDS.write(Setting.defaults(), false);
            db.submit(c -> {
                PlayerDao.saveSettings(c, typedId, soundsOff, "EU", "DE", 0);
                return null;
            }).join();
            int mask = Setting.AUTO_COUNTRY.write(0, false);
            assertEquals(1, db.submit(c -> PlayerDao.markCountriesManual(c, mask)).join());
            assertEquals(0, db.submit(c -> PlayerDao.markCountriesManual(c, mask)).join(), "only once per row");
            int typedBits = db.submit(c -> PlayerDao.findByUuid(c, typed)).join().orElseThrow().settings();
            int noneBits = db.submit(c -> PlayerDao.findByUuid(c, none)).join().orElseThrow().settings();
            assertFalse(Setting.AUTO_COUNTRY.read(typedBits), "a typed country keeps auto-detect off");
            assertFalse(Setting.SOUNDS.read(typedBits), "other settings are untouched");
            assertEquals(soundsOff | mask, typedBits);
            assertTrue(Setting.AUTO_COUNTRY.read(noneBits), "no country: detected");
            assertEquals(Setting.defaults(), noneBits);
        } finally {
            db.close();
        }
    }

    @Test
    void queueMusicIsBackfilledFromSchemaFive() {
        Database db = Database.sqlite(new File(dir.toFile(), "t5.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            UUID uuid = UUID.randomUUID();
            // a row from schema v5: friend alerts turned off by the player, no queue music bit yet
            int old = Setting.DUEL_REQUESTS.mask() | Setting.PARTY_INVITES.mask();
            db.submit(c -> PlayerDao.loadOrCreate(c, uuid, "Five", 1L, old)).join();
            db.submit(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE dc_meta SET v = '5' WHERE k = 'schema_version'")) {
                    ps.executeUpdate();
                }
                return null;
            }).join();
            assertEquals(Migrations.LATEST, db.submit(c -> Migrations.migrate(c, db.dialect())).join());
            int settings = db.submit(c -> PlayerDao.findByUuid(c, uuid)).join().orElseThrow().settings();
            assertEquals(old | Setting.QUEUE_MUSIC.mask(), settings);
            assertTrue((settings & Setting.FRIEND_ALERTS.mask()) == 0, "a setting the player turned off stays off");
        } finally {
            db.close();
        }
    }
}
