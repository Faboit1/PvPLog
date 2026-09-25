package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.FavoriteDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;

class FavoriteDaoTest {

    @TempDir
    Path dir;

    @Test
    void addRemoveLoad() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "f.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            int a = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Alpha", 1L, 0)).join().id();
            int b = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Bravo", 1L, 0)).join().id();
            db.submit(c -> {
                FavoriteDao.add(c, db.dialect(), a, 1);
                FavoriteDao.add(c, db.dialect(), a, 3);
                FavoriteDao.add(c, db.dialect(), a, 3); // duplicate is ignored
                FavoriteDao.add(c, db.dialect(), b, 2);
                return null;
            }).join();
            List<Integer> fav = db.submit(c -> FavoriteDao.load(c, a)).join();
            assertEquals(2, fav.size());
            assertTrue(fav.containsAll(List.of(1, 3)));
            db.submit(c -> {
                FavoriteDao.remove(c, a, 1);
                return null;
            }).join();
            assertEquals(List.of(3), db.submit(c -> FavoriteDao.load(c, a)).join());
            assertEquals(List.of(2), db.submit(c -> FavoriteDao.load(c, b)).join());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }
}
