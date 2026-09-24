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
    private int points;
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
        return (settings & s.mask()) != 0;
    }

    public void setting(Setting s, boolean value) {
        settings = value ? settings | s.mask() : settings & ~s.mask();
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
        points = 0;
        overall = null;
        recent = null;
    }

    public int points() {
        return points;
    }

    public @Nullable Tier overall() {
        return overall;
    }

    public void standing(int points, @Nullable Tier overall) {
        this.points = points;
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
