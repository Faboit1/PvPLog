package top.cheesesmp.duelcore.config;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Typed, immutable view of config.yml. Re-created on every reload. */
public final class MainConfig {

    // database
    public final String dbType;
    public final String sqliteFile;
    public final String mysqlHost;
    public final int mysqlPort;
    public final String mysqlDatabase;
    public final String mysqlUser;
    public final String mysqlPassword;
    public final int mysqlPoolSize;
    public final Map<String, String> mysqlProperties;

    // hub
    public final String hubWorld;
    public final boolean hubGeneratePlatform;
    public final long hubTime;
    public final boolean hubLockWeather;
    public final int hubVoidY;
    public final boolean hubShowPlayers;

    // queue
    public final boolean queueAllowMultiple;
    public final boolean queueRanked;
    public final boolean queueUnranked;
    public final boolean queueSearchingActionBar;

    // matchmaking
    public final int mmIntervalTicks;
    public final double mmWindowInitial;
    public final double mmWindowGrowth;
    public final double mmWindowMax;
    public final boolean mmRegionEnabled;
    public final double mmRegionPenalty;
    public final boolean mmPingEnabled;
    public final double mmPingPenaltyPerMs;
    public final double mmOverMaxPingPenalty;
    public final double mmRelaxAfterSeconds;
    public final int mmMaxRankedRematchesPerDay;
    public final boolean mmLogPairings;

    // match
    public final int countdownSeconds;
    public final int roundCountdownSeconds;
    public final int roundEndDelayTicks;
    public final int returnDelaySeconds;
    public final String timeoutDecision;
    public final int maxRounds;
    public final Set<String> allowedCommands;
    public final boolean totemPop;
    public final boolean animRespawnPull;
    public final int animRespawnPullTicks;
    public final boolean animDeath;
    public final boolean animRoundWin;
    public final boolean animMatchWin;
    public final boolean animFightStart;
    public final boolean animJoinTitle;
    public final int voidDepth;

    // rating
    public final String ratingSystem;
    public final double ratingDefault;
    public final double ratingFloor;
    public final double eloK;
    public final double eloProvisionalK;
    public final double glickoTau;
    public final double glickoDefaultRd;
    public final double glickoMinRd;
    public final double glickoDefaultVolatility;

    public final String firstSeasonName;

    // arena
    public final String arenaWorld;
    public final String editorWorld;
    public final int slotSpacing;
    public final int arenaBaseY;
    public final int maxInstances;
    public final int keepIdlePerTemplate;
    public final boolean prewarm;
    public final double blockBudgetMs;
    public final boolean resetBetweenRounds;
    public final int arenaViewDistance;
    public final boolean arenaPersistentWorld;
    public final int arenaPregenerateSlots;

    // leaderboard
    public final int leaderboardRefreshSeconds;
    public final int leaderboardSize;
    public final List<String> regions;

    // display
    public final boolean chatTag;
    public final boolean tabTag;
    public final boolean nametagTag;

    // web api
    public final boolean webEnabled;
    public final String webBind;
    public final int webPort;
    public final String webToken;
    public final int webCacheSeconds;

    // tournaments
    public final int tournamentMinPlayers;
    public final int tournamentMaxPlayers;
    public final int tournamentStartDelaySeconds;

    public final boolean verbose;

    public MainConfig(FileConfiguration c) {
        dbType = c.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        sqliteFile = c.getString("database.sqlite.file", "data.db");
        mysqlHost = c.getString("database.mysql.host", "localhost");
        mysqlPort = c.getInt("database.mysql.port", 3306);
        mysqlDatabase = c.getString("database.mysql.database", "duelcore");
        mysqlUser = c.getString("database.mysql.username", "duelcore");
        mysqlPassword = c.getString("database.mysql.password", "");
        mysqlPoolSize = c.getInt("database.mysql.pool-size", 6);
        mysqlProperties = strings(c.getConfigurationSection("database.mysql.properties"));

        hubWorld = c.getString("hub.world", "world");
        hubGeneratePlatform = c.getBoolean("hub.generate-platform-if-missing", true);
        hubTime = c.getLong("hub.time", 6000);
        hubLockWeather = c.getBoolean("hub.lock-weather", true);
        hubVoidY = c.getInt("hub.void-y", 0);
        hubShowPlayers = c.getBoolean("hub.show-players", true);

        queueAllowMultiple = c.getBoolean("queue.allow-multiple", false);
        queueRanked = c.getBoolean("queue.ranked", true);
        queueUnranked = c.getBoolean("queue.unranked", true);
        queueSearchingActionBar = c.getBoolean("queue.searching-action-bar", true);

        mmIntervalTicks = Math.max(1, c.getInt("matchmaking.interval-ticks", 20));
        mmWindowInitial = c.getDouble("matchmaking.window.initial", 50);
        mmWindowGrowth = c.getDouble("matchmaking.window.growth-per-second", 10);
        mmWindowMax = c.getDouble("matchmaking.window.max", 500);
        mmRegionEnabled = c.getBoolean("matchmaking.region.enabled", true);
        mmRegionPenalty = c.getDouble("matchmaking.region.cross-region-penalty", 250);
        mmPingEnabled = c.getBoolean("matchmaking.ping.enabled", true);
        mmPingPenaltyPerMs = c.getDouble("matchmaking.ping.penalty-per-ms", 0.5);
        mmOverMaxPingPenalty = c.getDouble("matchmaking.ping.over-max-ping-penalty", 300);
        mmRelaxAfterSeconds = c.getDouble("matchmaking.relax-after-seconds", 30);
        mmMaxRankedRematchesPerDay = c.getInt("matchmaking.max-ranked-rematches-per-day", 10);
        mmLogPairings = c.getBoolean("matchmaking.log-pairings", false);

        countdownSeconds = Math.max(0, c.getInt("match.countdown-seconds", 5));
        roundCountdownSeconds = Math.max(0, c.getInt("match.round-countdown-seconds", 3));
        roundEndDelayTicks = Math.max(1, c.getInt("match.round-end-delay-ticks", 50));
        returnDelaySeconds = Math.max(1, c.getInt("match.return-delay-seconds", 4));
        timeoutDecision = c.getString("match.timeout-decision", "health").toLowerCase(Locale.ROOT);
        maxRounds = Math.max(1, c.getInt("match.max-rounds", 15));
        allowedCommands = lower(c.getStringList("match.allowed-commands"));
        totemPop = c.getBoolean("match.totem-pop", true);
        animRespawnPull = c.getBoolean("animations.respawn-pull", true);
        animRespawnPullTicks = Math.clamp(c.getInt("animations.respawn-pull-ticks", 26), 6, 100);
        animDeath = c.getBoolean("animations.death", true);
        animRoundWin = c.getBoolean("animations.round-win", true);
        animMatchWin = c.getBoolean("animations.match-win", true);
        animFightStart = c.getBoolean("animations.fight-start", true);
        animJoinTitle = c.getBoolean("animations.join-title", true);
        voidDepth = Math.max(1, c.getInt("match.void-depth", 6));

        ratingSystem = c.getString("rating.system", "elo").toLowerCase(Locale.ROOT);
        ratingDefault = c.getDouble("rating.default", 1000);
        ratingFloor = c.getDouble("rating.floor", 100);
        eloK = c.getDouble("rating.elo.k-factor", 32);
        eloProvisionalK = c.getDouble("rating.elo.provisional-k-factor", 48);
        glickoTau = c.getDouble("rating.glicko2.tau", 0.5);
        glickoDefaultRd = c.getDouble("rating.glicko2.default-rd", 350);
        glickoMinRd = c.getDouble("rating.glicko2.min-rd", 45);
        glickoDefaultVolatility = c.getDouble("rating.glicko2.default-volatility", 0.06);

        firstSeasonName = c.getString("season.first-name", "Beta");

        arenaWorld = c.getString("arena.world", "arenas").toLowerCase(Locale.ROOT);
        editorWorld = c.getString("arena.editor-world", "editor").toLowerCase(Locale.ROOT);
        slotSpacing = Math.max(128, c.getInt("arena.slot-spacing", 1024));
        arenaBaseY = c.getInt("arena.base-y", 64);
        maxInstances = Math.max(2, c.getInt("arena.max-instances", 48));
        keepIdlePerTemplate = Math.max(0, c.getInt("arena.keep-idle-per-template", 2));
        prewarm = c.getBoolean("arena.prewarm", true);
        blockBudgetMs = Math.max(0.5, c.getDouble("arena.block-budget-ms", 4));
        resetBetweenRounds = c.getBoolean("arena.reset-between-rounds", true);
        arenaViewDistance = Math.clamp(c.getInt("arena.view-distance", 5), 2, 32);
        arenaPersistentWorld = c.getBoolean("arena.persistent-world", true);
        arenaPregenerateSlots = Math.clamp(c.getInt("arena.pregenerate-slots", 16), 0, 256);

        leaderboardRefreshSeconds = Math.max(5, c.getInt("leaderboard.refresh-seconds", 30));
        leaderboardSize = Math.clamp(c.getInt("leaderboard.size", 100), 10, 1000);
        regions = c.getStringList("leaderboard.regions").stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();

        chatTag = c.getBoolean("display.chat-tag", true);
        tabTag = c.getBoolean("display.tab-tag", true);
        nametagTag = c.getBoolean("display.nametag-tag", true);

        webEnabled = c.getBoolean("web-api.enabled", false);
        webBind = c.getString("web-api.bind", "0.0.0.0");
        webPort = c.getInt("web-api.port", 8765);
        webToken = c.getString("web-api.token", "change-me");
        webCacheSeconds = Math.max(0, c.getInt("web-api.cache-seconds", 10));

        tournamentMinPlayers = Math.max(2, c.getInt("tournaments.min-players", 4));
        tournamentMaxPlayers = Math.max(tournamentMinPlayers, c.getInt("tournaments.max-players", 64));
        tournamentStartDelaySeconds = Math.max(1, c.getInt("tournaments.start-delay-seconds", 10));

        verbose = c.getBoolean("debug.verbose", false);
    }

    private static Map<String, String> strings(ConfigurationSection section) {
        Map<String, String> map = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) map.put(key, String.valueOf(section.get(key)));
        }
        return map;
    }

    private static Set<String> lower(List<String> list) {
        Set<String> set = new LinkedHashSet<>();
        for (String s : list) set.add(s.toLowerCase(Locale.ROOT).replace("/", ""));
        return set;
    }
}
