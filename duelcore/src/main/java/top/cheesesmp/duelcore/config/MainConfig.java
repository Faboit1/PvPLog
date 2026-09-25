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
    public final boolean hubAllowFlight;

    // queue
    public final boolean queueAllowMultiple;
    public final boolean queueRanked;
    public final boolean queueUnranked;
    public final boolean queueSearchingActionBar;
    public final boolean queueMusicEnabled;
    /** Keep Minecraft's own background music (the MUSIC sound source) off for everyone. */
    public final boolean stopClientMusic;
    public final float queueMusicVolume;
    public final top.cheesesmp.duelcore.queue.MusicTracks queueMusicTracks;

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
    public final boolean animRespawnThrow;
    public final double animRespawnThrowHeight;
    /** Players with a higher ping are teleported instead of thrown (0 = always throw). */
    public final int animRespawnThrowMaxPing;
    /** Ticks (plus the player's ping) after which a throw the server never saw move is replaced by a teleport. */
    public final int animRespawnThrowStallTicks;
    public final boolean animDeath;
    public final boolean animRoundWin;
    public final boolean animMatchWin;
    public final boolean animFightStart;
    public final boolean animJoinTitle;
    /** Hub ambience: the typed welcome title (with join-title), the sidebar title shimmer, the tab logo wave. */
    public final boolean animJoinWelcome;
    public final boolean animSidebarTitle;
    public final boolean animTabLogo;
    /** The hub XP bar showing overall progress, its animated fill after a match and the tier-up ring. */
    public final boolean animHubXp;
    public final boolean animHubXpFill;
    public final boolean animTierRing;
    /** Friend and party alerts: a short action bar pop next to the chat line. */
    public final boolean animAlertPops;
    /** Sounds of the hub animations above (welcome chime, XP orbs, alert pops, ring). */
    public final boolean animHubSounds;
    public final boolean animSpawnRise;
    public final int animSpawnRiseDepth;
    public final int animSpawnRiseTicks;
    /** Post-match progress in the hub action bar (count up, blink, fade) and its fade time in ticks. */
    public final boolean animProgressReveal;
    public final int animProgressFadeTicks;
    /** Title celebrations for a better tier / the first tier after placement, and the quiet demotion subtitle. */
    public final boolean animTierUp;
    public final boolean animPlaced;
    public final boolean animTierDown;
    /** Fireworks around the player (only they see them) during the tier-up and placed celebrations. */
    public final boolean animCelebrationParticles;
    /** Tick sounds while counting, flourishes (players' own sound setting still applies). */
    public final boolean animProgressSounds;
    /**
     * The queue experience: the queue menu's progress fill, the animated searching action bar, the searching boss
     * bar, the animated "match found" title, and their sounds (ticks, flourish, whoosh).
     */
    public final boolean animQueueProgress;
    public final boolean animSearchingBar;
    public final boolean animMatchFound;
    public final boolean animQueueSounds;
    /**
     * In-match animations (ui/MatchFx): popping countdown, "FIGHT!" sweep, round banner, match point pulse, combo /
     * kill bar, low-health heartbeat (at or below heartbeat-hearts), victory / defeat titles, confetti, FFA players left.
     */
    public final boolean animCountdownPop;
    public final boolean animFightSweep;
    public final boolean animRoundBanner;
    public final boolean animMatchPoint;
    public final boolean animComboBar;
    public final boolean animHeartbeat;
    public final double animHeartbeatHearts;
    public final boolean animVictoryTitle;
    public final boolean animDefeatTitle;
    public final boolean animVictoryConfetti;
    public final boolean animPlayersLeft;
    public final top.cheesesmp.duelcore.ui.SoundPool matchFoundSounds;
    public final top.cheesesmp.duelcore.ui.SoundPool fightStartSounds;
    /** Sound lines (match-found-sounds, fight-start-sounds, queue music) that could not be read (reported on load and reload). */
    public final List<String> soundProblems = new java.util.ArrayList<>();
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

    // parties
    public final int partyMaxSize;
    public final int partyInviteSeconds;
    public final boolean partyOpenByDefault;

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
        hubAllowFlight = c.getBoolean("hub.allow-flight", true);

        queueAllowMultiple = c.getBoolean("queue.allow-multiple", true);
        queueRanked = c.getBoolean("queue.ranked", true);
        queueUnranked = c.getBoolean("queue.unranked", false);
        queueSearchingActionBar = c.getBoolean("queue.searching-action-bar", true);
        queueMusicEnabled = c.getBoolean("queue.music.enabled", true);
        stopClientMusic = c.getBoolean("queue.music.stop-client-music", true);
        queueMusicVolume = (float) Math.clamp(c.getDouble("queue.music.volume", 0.5), 0.0, 1.0);
        queueMusicTracks = top.cheesesmp.duelcore.queue.MusicTracks.parse(c.getStringList("queue.music.tracks"));
        for (String p : queueMusicTracks.problems()) soundProblems.add("queue.music.tracks: " + p);

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
        animRespawnThrow = c.getBoolean("animations.respawn-throw", true);
        animRespawnThrowHeight = Math.clamp(c.getDouble("animations.respawn-throw-height", 10), 2, 40);
        animRespawnThrowMaxPing = Math.max(0, c.getInt("animations.respawn-throw-max-ping", 350));
        animRespawnThrowStallTicks = Math.clamp(c.getInt("animations.respawn-throw-stall-ticks", 10), 4, 60);
        animDeath = c.getBoolean("animations.death", true);
        animRoundWin = c.getBoolean("animations.round-win", true);
        animMatchWin = c.getBoolean("animations.match-win", true);
        animFightStart = c.getBoolean("animations.fight-start", true);
        animJoinTitle = c.getBoolean("animations.join-title", true);
        animJoinWelcome = c.getBoolean("animations.join-welcome", true);
        animSidebarTitle = c.getBoolean("animations.sidebar-title", true);
        animTabLogo = c.getBoolean("animations.tab-logo", true);
        animHubXp = c.getBoolean("animations.hub-xp-bar", true);
        animHubXpFill = c.getBoolean("animations.hub-xp-fill", true);
        animTierRing = c.getBoolean("animations.tier-ring", true);
        animAlertPops = c.getBoolean("animations.alert-pops", true);
        animHubSounds = c.getBoolean("animations.hub-sounds", true);
        animSpawnRise = c.getBoolean("animations.spawn-rise", true);
        animSpawnRiseDepth = Math.clamp(c.getInt("animations.spawn-rise-depth", 3), 1, 6);
        animSpawnRiseTicks = Math.clamp(c.getInt("animations.spawn-rise-ticks", 50), 10, 60);
        animProgressReveal = c.getBoolean("animations.progress-reveal", true);
        animProgressFadeTicks = (int) Math.round(Math.clamp(c.getDouble("animations.progress-fade-seconds", 3), 0.5, 10) * 20);
        animTierUp = c.getBoolean("animations.tier-up", true);
        animPlaced = c.getBoolean("animations.placed", true);
        animTierDown = c.getBoolean("animations.tier-down", true);
        animCelebrationParticles = c.getBoolean("animations.celebration-particles", true);
        animProgressSounds = c.getBoolean("animations.progress-sounds", true);
        animQueueProgress = c.getBoolean("animations.queue-progress", true);
        animSearchingBar = c.getBoolean("animations.searching-bar", true);
        animMatchFound = c.getBoolean("animations.match-found-reveal", true);
        animQueueSounds = c.getBoolean("animations.queue-sounds", true);
        animCountdownPop = c.getBoolean("animations.countdown-pop", true);
        animFightSweep = c.getBoolean("animations.fight-sweep", true);
        animRoundBanner = c.getBoolean("animations.round-banner", true);
        animMatchPoint = c.getBoolean("animations.match-point", true);
        animComboBar = c.getBoolean("animations.combo-bar", true);
        animHeartbeat = c.getBoolean("animations.heartbeat", true);
        animHeartbeatHearts = Math.clamp(c.getDouble("animations.heartbeat-hearts", 3), 0.5, 10);
        animVictoryTitle = c.getBoolean("animations.victory-title", true);
        animDefeatTitle = c.getBoolean("animations.defeat-title", true);
        animVictoryConfetti = c.getBoolean("animations.victory-confetti", true);
        animPlayersLeft = c.getBoolean("animations.players-left", true);
        matchFoundSounds = top.cheesesmp.duelcore.ui.SoundPool.parse(c.getStringList("animations.match-found-sounds"));
        var fightStart = top.cheesesmp.duelcore.ui.SoundPool.parse(c.getStringList("animations.fight-start-sounds"));
        for (String p : matchFoundSounds.problems()) soundProblems.add("animations.match-found-sounds: " + p);
        for (String p : fightStart.problems()) soundProblems.add("animations.fight-start-sounds: " + p);
        // a round always started with a pling; keep it when every configured line is broken
        fightStartSounds = fightStart.combos().isEmpty() && !fightStart.problems().isEmpty()
            ? top.cheesesmp.duelcore.ui.SoundPool.parse(List.of("block.note_block.pling 1.6")) : fightStart;
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
        arenaViewDistance = Math.clamp(c.getInt("arena.view-distance", 7), 2, 32);
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

        partyMaxSize = Math.clamp(c.getInt("party.max-size", 20), 2, 100);
        partyInviteSeconds = Math.clamp(c.getInt("party.invite-seconds", 60), 10, 600);
        partyOpenByDefault = c.getBoolean("party.open-by-default", false);

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
