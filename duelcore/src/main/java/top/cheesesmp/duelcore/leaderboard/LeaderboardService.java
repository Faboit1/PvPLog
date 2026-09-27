package top.cheesesmp.duelcore.leaderboard;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.dao.LeaderboardDao;

/**
 * Cached leaderboards. A board is fetched on first request, kept for {@code refresh-seconds}, refreshed in the
 * background while it's being viewed, and invalidated when a match of its kit ends. Boards can be filtered by a
 * region or a country; ranks are counted within the filter, so a country board ranks its players 1, 2, 3, …
 */
public final class LeaderboardService implements Runnable {

    public static final String OVERALL = "overall";

    /** Filter: region code or country code (exactly one kind, or neither). */
    public record Key(String category, @Nullable String region, @Nullable String country) {
    }

    private record Board(List<LeaderboardDao.Row> rows, long fetchedAt, long lastViewed) {
    }

    private final DuelCorePlugin plugin;
    private final Database db;
    private final Map<Key, Board> boards = new ConcurrentHashMap<>();
    private final Map<Key, CompletableFuture<List<LeaderboardDao.Row>>> inflight = new ConcurrentHashMap<>();
    private volatile CountryNames countries;

    public LeaderboardService(DuelCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.countries = CountryNames.load(plugin);
    }

    /** {@code /duelcore reload}: drops the cached boards and reads the country names again. */
    public void reload() {
        clear();
        countries = CountryNames.load(plugin);
    }

    /** A country's name from countries.yml, or its code when it isn't listed there. */
    public String countryName(String code) {
        String name = countries.name(code);
        return name != null ? name : code.toUpperCase(Locale.ROOT);
    }

    /**
     * {@code raw} as a country a board may be filtered by: two letters that countries.yml lists, or the viewer's own
     * country ({@code own}, which players may set to any two letters). Null for anything else: dialog payloads and
     * command arguments are untrusted, and this also bounds how many country boards can be cached.
     */
    public @Nullable String countryFilter(@Nullable String raw, @Nullable String own) {
        if (raw == null) return null;
        String code = raw.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{2}")) return null;
        return countries.known(code) || code.equalsIgnoreCase(own) ? code : null;
    }

    /** Every country code countries.yml lists, A–Z. */
    public Set<String> countryCodes() {
        return countries.codes();
    }

    public CompletableFuture<List<LeaderboardDao.Row>> get(String category, @Nullable String region, @Nullable String country) {
        Key key = new Key(category.toLowerCase(Locale.ROOT), region == null ? null : region.toUpperCase(Locale.ROOT),
            country == null ? null : country.toUpperCase(Locale.ROOT));
        Board board = boards.get(key);
        long now = System.currentTimeMillis();
        if (board != null) {
            boards.put(key, new Board(board.rows(), board.fetchedAt(), now));
            if (now - board.fetchedAt() < plugin.settings().leaderboardRefreshSeconds * 1000L) {
                return CompletableFuture.completedFuture(board.rows());
            }
        }
        return fetch(key);
    }

    private CompletableFuture<List<LeaderboardDao.Row>> fetch(Key key) {
        CompletableFuture<List<LeaderboardDao.Row>> running = inflight.get(key);
        if (running != null) return running;
        int season = plugin.profiles().season().id();
        int limit = plugin.settings().leaderboardSize;
        int placement = plugin.tiers().placementMatches();
        CompletableFuture<List<LeaderboardDao.Row>> f;
        if (OVERALL.equals(key.category())) {
            f = db.submit(c -> LeaderboardDao.overall(c, season, key.region(), key.country(), limit));
        } else {
            int kitId;
            try {
                kitId = plugin.profiles().kitId(key.category());
            } catch (IllegalStateException e) {
                return CompletableFuture.completedFuture(List.of());
            }
            f = db.submit(c -> LeaderboardDao.kit(c, season, kitId, placement, key.region(), key.country(), limit));
        }
        // registered before the completion callback: the query may already be done (it takes well under a
        // millisecond), and the callback must be able to take it out again. (It used to be removed from inside
        // computeIfAbsent, which throws when the query finished first and left a failed fetch in the map for good:
        // that board never opened again until a restart.)
        CompletableFuture<List<LeaderboardDao.Row>> prev = inflight.putIfAbsent(key, f);
        if (prev != null) return prev;
        f.whenComplete((rows, err) -> {
            inflight.remove(key, f);
            if (rows != null) boards.put(key, new Board(rows, System.currentTimeMillis(), System.currentTimeMillis()));
        });
        return f;
    }

    /** Marks a kit's boards and the overall boards stale. */
    public void invalidate(String kit) {
        boards.replaceAll((k, b) -> k.category().equals(kit) || k.category().equals(OVERALL)
            ? new Board(b.rows(), 0, b.lastViewed()) : b);
    }

    public void clear() {
        boards.clear();
    }

    public int cachedBoards() {
        return boards.size();
    }

    public CompletableFuture<Integer> kitRank(String kit, double rating) {
        int season = plugin.profiles().season().id();
        int placement = plugin.tiers().placementMatches();
        int kitId = plugin.profiles().kitId(kit);
        return db.submit(c -> LeaderboardDao.kitRank(c, season, kitId, placement, rating));
    }

    public CompletableFuture<Integer> overallRank(int elo) {
        int season = plugin.profiles().season().id();
        return db.submit(c -> LeaderboardDao.overallRank(c, season, elo));
    }

    /** Background refresh of boards viewed in the last 5 minutes; drops the rest. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        long ttl = plugin.settings().leaderboardRefreshSeconds * 1000L;
        boards.entrySet().removeIf(e -> now - e.getValue().lastViewed() > 300_000);
        for (Map.Entry<Key, Board> e : boards.entrySet()) {
            if (now - e.getValue().fetchedAt() >= ttl) fetch(e.getKey());
        }
    }
}
