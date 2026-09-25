package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.MatchDao;
import top.cheesesmp.duelcore.db.dao.MetaDao;
import top.cheesesmp.duelcore.db.dao.PartyDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;

class PartyDaoTest {

    @TempDir
    Path dir;

    @Test
    void partiesRoundTrip() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "p.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            UUID ua = UUID.randomUUID();
            UUID ub = UUID.randomUUID();
            UUID uc = UUID.randomUUID();
            int a = db.submit(c -> PlayerDao.loadOrCreate(c, ua, "Alpha", 1L, 0)).join().id();
            int b = db.submit(c -> PlayerDao.loadOrCreate(c, ub, "Bravo", 1L, 0)).join().id();
            int cc = db.submit(c -> PlayerDao.loadOrCreate(c, uc, "Charlie", 1L, 0)).join().id();

            db.transaction(c -> {
                PartyDao.insertParty(c, "p1", a, false, null, 100L);
                PartyDao.addMember(c, db.dialect(), "p1", a, 100L, false);
                PartyDao.addMember(c, db.dialect(), "p1", b, 200L, true);
                PartyDao.insertParty(c, "p2", cc, true, "p1$hash", 150L);
                PartyDao.addMember(c, db.dialect(), "p2", cc, 150L, false);
                return null;
            }).join();

            List<PartyDao.PartyRow> rows = db.submit(PartyDao::loadAll).join();
            assertEquals(2, rows.size());
            PartyDao.PartyRow p1 = rows.get(0);
            assertEquals("p1", p1.id());
            assertEquals(a, p1.leaderId());
            assertFalse(p1.open());
            assertNull(p1.password());
            assertEquals(List.of(ua, ub), p1.members().stream().map(PartyDao.MemberRow::uuid).toList());
            assertEquals("Bravo", p1.members().get(1).name());
            assertTrue(p1.members().get(1).chat());
            PartyDao.PartyRow p2 = rows.get(1);
            assertTrue(p2.open());
            assertEquals("p1$hash", p2.password());

            // leader change + privacy, chat toggle, a member moving to another party (player_id is the key)
            db.transaction(c -> {
                PartyDao.updateParty(c, "p1", b, true, "p1$x");
                PartyDao.setChat(c, b, false);
                PartyDao.addMember(c, db.dialect(), "p2", a, 300L, false);
                return null;
            }).join();
            rows = db.submit(PartyDao::loadAll).join();
            p1 = rows.get(0);
            assertEquals(b, p1.leaderId());
            assertTrue(p1.open());
            assertEquals("p1$x", p1.password());
            assertEquals(1, p1.members().size());
            assertFalse(p1.members().get(0).chat());
            assertEquals(List.of(uc, ua), rows.get(1).members().stream().map(PartyDao.MemberRow::uuid).toList());

            // removing the last member leaves an empty party: cleanup deletes it; orphan members are dropped too
            db.transaction(c -> {
                PartyDao.removeMember(c, b);
                PartyDao.addMember(c, db.dialect(), "ghost", b, 400L, false);
                return null;
            }).join();
            int removed = db.submit(PartyDao::cleanup).join();
            assertEquals(2, removed); // the ghost member row + the empty party p1
            rows = db.submit(PartyDao::loadAll).join();
            assertEquals(1, rows.size());
            assertEquals("p2", rows.get(0).id());

            db.submit(c -> {
                PartyDao.deleteParty(c, "p2");
                return null;
            }).join();
            assertTrue(db.submit(PartyDao::loadAll).join().isEmpty());
            assertEquals(0, db.submit(PartyDao::cleanup).join());

            // offline notices live in dc_meta and survive until cleared
            db.submit(c -> {
                PartyDao.setNotice(c, db.dialect(), a, "kicked:Bravo");
                PartyDao.setNotice(c, db.dialect(), cc, "disbanded:Charlie");
                PartyDao.setNotice(c, db.dialect(), a, "disbanded:Bravo"); // replaces
                MetaDao.set(c, db.dialect(), "party-notice:oops", "ignored");
                return null;
            }).join();
            List<PartyDao.Notice> notices = new ArrayList<>(db.submit(PartyDao::loadNotices).join());
            notices.sort(java.util.Comparator.comparingInt(PartyDao.Notice::playerId));
            assertEquals(2, notices.size());
            assertEquals(ua, notices.get(0).uuid());
            assertEquals("disbanded:Bravo", notices.get(0).text());
            assertEquals(uc, notices.get(1).uuid());
            db.submit(c -> {
                PartyDao.clearNotice(c, a);
                return null;
            }).join();
            assertEquals(1, db.submit(PartyDao::loadNotices).join().size());

            // party matches: a big free-for-all is one history line and doesn't push older matches out
            Map<String, Integer> kits = db.submit(c -> MetaDao.syncKits(c, List.of("sword"))).join();
            int sword = kits.get("sword");
            int season = db.submit(c -> MetaDao.currentSeason(c, "S", 1L)).join().id();
            List<Integer> ffa = new ArrayList<>(List.of(a, b, cc));
            for (int i = 0; i < 12; i++) {
                String name = "Extra" + i;
                ffa.add(db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), name, 1L, 0)).join().id());
            }
            db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(season, sword, false, "box", 10L, 1000, 0, 1, 3,
                "1", List.of(new MatchDao.ParticipantRecord(a, 0, 1, 1, 1f, 1f, 1000f, 1000f),
                    new MatchDao.ParticipantRecord(b, 1, 0, 1, 1f, 1f, 1000f, 1000f))))).join();
            List<MatchDao.ParticipantRecord> records = new ArrayList<>();
            for (int t = 0; t < ffa.size(); t++) {
                records.add(new MatchDao.ParticipantRecord(ffa.get(t), t, t == 0 ? 1 : 0, 0, 0f, 0f, 1000f, 1000f));
            }
            db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(season, sword, false, "box", 20L, 1000, 0, 0, 1,
                "0", records))).join();
            List<MatchDao.HistoryEntry> history = db.submit(c -> MatchDao.history(c, a, 2, Map.of(sword, "sword"))).join();
            assertEquals(2, history.size());
            assertTrue(history.get(0).won());
            assertEquals("Bravo", history.get(0).opponentName()); // lowest player id of the others
            assertEquals("Bravo", history.get(1).opponentName());

            assertEquals(0, db.mainThreadExecutions());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }
}
