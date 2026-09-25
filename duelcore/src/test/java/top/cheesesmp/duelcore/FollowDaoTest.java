package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import top.cheesesmp.duelcore.db.dao.FollowDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;

class FollowDaoTest {

    @TempDir
    Path dir;

    @Test
    void followsAndFriends() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "f.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            UUID ua = UUID.randomUUID();
            UUID ub = UUID.randomUUID();
            UUID uc = UUID.randomUUID();
            int a = db.submit(c -> PlayerDao.loadOrCreate(c, ua, "Alpha", 1L, 0)).join().id();
            int b = db.submit(c -> PlayerDao.loadOrCreate(c, ub, "Bravo", 1L, 0)).join().id();
            int cc = db.submit(c -> PlayerDao.loadOrCreate(c, uc, "Charlie", 1L, 0)).join().id();

            assertTrue(db.submit(c -> FollowDao.follow(c, db.dialect(), a, b, 10L)).join());
            assertFalse(db.submit(c -> FollowDao.follow(c, db.dialect(), a, b, 11L)).join(), "duplicate follow");
            assertFalse(db.submit(c -> FollowDao.follow(c, db.dialect(), a, a, 11L)).join(), "self follow");
            assertTrue(db.submit(c -> FollowDao.follow(c, db.dialect(), b, a, 12L)).join());
            assertTrue(db.submit(c -> FollowDao.follow(c, db.dialect(), a, cc, 13L)).join());

            assertTrue(db.submit(c -> FollowDao.isFollowing(c, a, b)).join());
            assertTrue(db.submit(c -> FollowDao.isFollowing(c, b, a)).join());
            assertFalse(db.submit(c -> FollowDao.isFollowing(c, cc, a)).join());

            FollowDao.Relations ra = db.submit(c -> FollowDao.load(c, a)).join();
            assertEquals(2, ra.following().size());
            assertEquals(1, ra.followers().size());
            FollowDao.Person bravo = ra.followers().getFirst();
            assertEquals(b, bravo.id());
            assertEquals(ub, bravo.uuid());
            assertEquals("Bravo", bravo.name());
            assertEquals(12L, bravo.since());
            FollowDao.Person followedC = ra.following().stream().filter(p -> p.id() == cc).findFirst().orElseThrow();
            assertEquals("Charlie", followedC.name());
            assertEquals(uc, followedC.uuid());
            assertEquals(13L, followedC.since());

            List<FollowDao.Person> cFollowers = db.submit(c -> FollowDao.followers(c, cc)).join();
            assertEquals(List.of(a), cFollowers.stream().map(FollowDao.Person::id).toList());
            assertTrue(db.submit(c -> FollowDao.following(c, cc)).join().isEmpty());

            // renames show up through the join
            db.submit(c -> PlayerDao.loadOrCreate(c, ub, "BravoNew", 20L, 0)).join();
            assertEquals("BravoNew", db.submit(c -> FollowDao.following(c, a)).join().stream()
                .filter(p -> p.id() == b).findFirst().orElseThrow().name());

            assertTrue(db.submit(c -> FollowDao.unfollow(c, a, cc)).join());
            assertFalse(db.submit(c -> FollowDao.unfollow(c, a, cc)).join(), "already removed");
            assertTrue(db.submit(c -> FollowDao.followers(c, cc)).join().isEmpty());
            assertEquals(1, db.submit(c -> FollowDao.following(c, a)).join().size());
            assertEquals(0, db.failures());
            assertEquals(0, db.mainThreadExecutions());
        } finally {
            db.close();
        }
    }
}
