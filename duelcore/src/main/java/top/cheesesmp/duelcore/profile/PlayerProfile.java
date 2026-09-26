package top.cheesesmp.duelcore.profile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.db.dao.MatchDao;
import top.cheesesmp.duelcore.rating.Tier;

/** Cached data of one player for the current season. Mutated on the main thread only. */
public final class PlayerProfile {

    private final int id;
    private final UUID uuid;
    private String name;
    private int settings;
    private @Nullable String region;
    private @Nullable String country;
    private int maxPing;
    private final Map<String, KitStats> stats;
    private int elo;
    private @Nullable Tier overall;
    private @Nullable List<MatchDao.HistoryEntry> recent;

    public PlayerProfile(int id, UUID uuid, String name, int settings, @Nullable String region, @Nullable String country,
                         int maxPing, Map<String, KitStats> stats) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.settings = settings;
        this.region = region;
        this.country = country;
        this.maxPing = maxPing;
        this.stats = new HashMap<>(stats);
    }

    public int id() {
        return id;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public int settingsBits() {
        return settings;
    }

    public boolean setting(Setting s) {
        return s.read(settings);
    }

    public void setting(Setting s, boolean value) {
        settings = s.write(settings, value);
    }

    /** Who may send this player duel requests ({@link Setting#DUEL_REQUESTS} and {@link Setting#DUEL_FRIENDS_ONLY}). */
    public DuelRequests duelRequests() {
        return DuelRequests.of(setting(Setting.DUEL_REQUESTS), setting(Setting.DUEL_FRIENDS_ONLY));
    }

    public void duelRequests(DuelRequests who) {
        setting(Setting.DUEL_REQUESTS, who != DuelRequests.NOBODY);
        setting(Setting.DUEL_FRIENDS_ONLY, who == DuelRequests.FRIENDS);
    }

    public @Nullable String region() {
        return region;
    }

    public void region(@Nullable String region) {
        this.region = region;
    }

    public @Nullable String country() {
        return country;
    }

    public void country(@Nullable String country) {
        this.country = country;
    }

    public int maxPing() {
        return maxPing;
    }

    public void maxPing(int maxPing) {
        this.maxPing = maxPing;
    }

    /** Stats for a kit, or null when never played this season. */
    public @Nullable KitStats stats(String kit) {
        return stats.get(kit);
    }

    public KitStats statsOrCreate(String kit, double rating, double rd, double volatility) {
        return stats.computeIfAbsent(kit, k -> new KitStats(rating, rd, volatility));
    }

    public Map<String, KitStats> allStats() {
        return stats;
    }

    public void clearStats() {
        stats.clear();
        elo = 0;
        overall = null;
        recent = null;
    }

    /** Overall Elo (average rating of the kits with finished placement). */
    public int elo() {
        return elo;
    }

    public @Nullable Tier overall() {
        return overall;
    }

    public void standing(int elo, @Nullable Tier overall) {
        this.elo = elo;
        this.overall = overall;
    }

    public @Nullable List<MatchDao.HistoryEntry> recent() {
        return recent;
    }

    public void recent(@Nullable List<MatchDao.HistoryEntry> recent) {
        this.recent = recent;
    }

    public int totalWins() {
        int n = 0;
        for (KitStats s : stats.values()) n += s.wins;
        return n;
    }

    public int totalLosses() {
        int n = 0;
        for (KitStats s : stats.values()) n += s.losses;
        return n;
    }
}
