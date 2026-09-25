package top.cheesesmp.duelcore.kit.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.KitLayoutDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;

class KitLayoutDaoTest {

    @TempDir
    Path dir;

    @Test
    void saveReplaceLoadDelete() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "k.db"), Logger.getLogger("test"), () -> false);
        try {
            assertEquals(Migrations.LATEST, db.submit(c -> Migrations.migrate(c, db.dialect())).join());
            int a = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Alpha", 1L, 0)).join().id();
            int b = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Bravo", 1L, 0)).join().id();
            boolean[] filled = new boolean[KitLayout.SIZE];
            filled[0] = filled[1] = filled[KitLayout.OFFHAND] = true;
            int[] swapped = KitLayout.identity(filled);
            swapped[0] = 1;
            swapped[1] = 0;
            int[] offhand = KitLayout.identity(filled);
            offhand[0] = KitLayout.OFFHAND;
            offhand[KitLayout.OFFHAND] = 0;
            db.submit(c -> {
                KitLayoutDao.save(c, db.dialect(), a, 1, KitLayout.encode(swapped), 111, 10L);
                KitLayoutDao.save(c, db.dialect(), a, 2, KitLayout.encode(offhand), 222, 11L);
                KitLayoutDao.save(c, db.dialect(), b, 1, KitLayout.encode(offhand), 333, 12L);
                // saving again replaces the row (upsert on player + kit)
                KitLayoutDao.save(c, db.dialect(), a, 1, KitLayout.encode(offhand), 444, 13L);
                return null;
            }).join();
            Map<Integer, KitLayoutDao.Row> rows = db.submit(c -> KitLayoutDao.load(c, a)).join().stream()
                .collect(Collectors.toMap(KitLayoutDao.Row::kitId, r -> r));
            assertEquals(2, rows.size());
            assertArrayEquals(offhand, KitLayout.decode(rows.get(1).layout()));
            assertEquals(444, rows.get(1).kitHash());
            assertEquals(13L, rows.get(1).updatedAt());
            assertEquals(222, rows.get(2).kitHash());
            assertTrue(KitLayout.valid(KitLayout.decode(rows.get(2).layout()), filled));

            db.submit(c -> {
                KitLayoutDao.delete(c, a, 1);
                KitLayoutDao.delete(c, a, 9); // nothing there: no error
                return null;
            }).join();
            List<KitLayoutDao.Row> left = db.submit(c -> KitLayoutDao.load(c, a)).join();
            assertEquals(1, left.size());
            assertEquals(2, left.getFirst().kitId());
            List<KitLayoutDao.Row> other = db.submit(c -> KitLayoutDao.load(c, b)).join();
            assertEquals(1, other.size(), "other players' layouts are untouched");
            assertEquals(333, other.getFirst().kitHash());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }

    @Test
    void negativeHashesSurvive() throws Exception {
        // CRC-32 values above 2^31 are stored as negative ints
        Database db = Database.sqlite(new File(dir.toFile(), "n.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            int a = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Alpha", 1L, 0)).join().id();
            String layout = KitLayout.encode(KitLayout.identity(new boolean[KitLayout.SIZE]));
            db.submit(c -> {
                KitLayoutDao.save(c, db.dialect(), a, 3, layout, Integer.MIN_VALUE + 7, 1L);
                return null;
            }).join();
            assertEquals(Integer.MIN_VALUE + 7, db.submit(c -> KitLayoutDao.load(c, a)).join().getFirst().kitHash());
        } finally {
            db.close();
        }
    }
}
