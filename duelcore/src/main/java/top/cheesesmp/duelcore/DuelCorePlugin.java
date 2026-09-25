package top.cheesesmp.duelcore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import top.cheesesmp.duelcore.api.DuelCoreApi;
import top.cheesesmp.duelcore.arena.ArenaEditor;
import top.cheesesmp.duelcore.arena.ArenaManager;
import top.cheesesmp.duelcore.command.CommandService;
import top.cheesesmp.duelcore.config.ConfigManager;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.debug.Diagnostics;
import top.cheesesmp.duelcore.hub.HubListener;
import top.cheesesmp.duelcore.hub.HubService;
import top.cheesesmp.duelcore.hub.VisibilityService;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.leaderboard.LeaderboardService;
import top.cheesesmp.duelcore.match.DuelRequestService;
import top.cheesesmp.duelcore.match.MatchListener;
import top.cheesesmp.duelcore.match.MatchService;
import top.cheesesmp.duelcore.match.SpectateService;
import top.cheesesmp.duelcore.profile.ProfileService;
import top.cheesesmp.duelcore.queue.QueueService;
import top.cheesesmp.duelcore.rating.EloRating;
import top.cheesesmp.duelcore.rating.Glicko2Rating;
import top.cheesesmp.duelcore.rating.RatingSystem;
import top.cheesesmp.duelcore.rating.TierService;
import top.cheesesmp.duelcore.ui.ResultsService;
import top.cheesesmp.duelcore.ui.SidebarService;
import top.cheesesmp.duelcore.ui.TagService;
import top.cheesesmp.duelcore.ui.dialog.ClickRouter;
import top.cheesesmp.duelcore.ui.dialog.DialogService;

/** DuelCore: competitive 1v1 duels, ranked queues, tiers and seasons. */
public final class DuelCorePlugin extends JavaPlugin {

    private ConfigManager config;
    private Database database;
    private KitManager kits;
    private ProfileService profiles;
    private RatingSystem ratingSystem;
    private ArenaManager arenas;
    private ArenaEditor editor;
    private HubService hub;
    private VisibilityService visibility;
    private SidebarService sidebar;
    private TagService tags;
    private QueueService queue;
    private MatchService matches;
    private SpectateService spectate;
    private DuelRequestService duels;
    private ResultsService results;
    private top.cheesesmp.duelcore.ui.RespawnPull respawnPull;
    private top.cheesesmp.duelcore.ui.Animations animations;
    private DialogService dialogs;
    private ClickRouter clicks;
    private LeaderboardService leaderboards;
    private CommandService commands;
    private Diagnostics diagnostics;
    private top.cheesesmp.duelcore.party.PartyService parties;
    private boolean papiHooked;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        getDataFolder().mkdirs();
        new File(getDataFolder(), "schematics").mkdirs();
        config = new ConfigManager(this);
        config.load();
        kits = new KitManager(this);
        kits.load();

        MainConfig cfg = settings();
        database = "mysql".equals(cfg.dbType)
            ? Database.mysql(cfg.mysqlHost, cfg.mysqlPort, cfg.mysqlDatabase, cfg.mysqlUser, cfg.mysqlPassword,
                cfg.mysqlPoolSize, cfg.mysqlProperties, getLogger(), Bukkit::isPrimaryThread)
            : Database.sqlite(new File(getDataFolder(), cfg.sqliteFile), getLogger(), Bukkit::isPrimaryThread);
        profiles = new ProfileService(this, database);
        profiles.start(kits.ids(), cfg.firstSeasonName).exceptionally(e -> {
            getLogger().log(Level.SEVERE, "Database setup failed; players will not be able to join", e);
            return null;
        });
        ratingSystem = buildRatingSystem();

        hub = new HubService(this);
        visibility = new VisibilityService(this);
        sidebar = new SidebarService(this);
        tags = new TagService(this);
        queue = new QueueService(this);
        matches = new MatchService(this);
        spectate = new SpectateService(this);
        duels = new DuelRequestService(this);
        results = new ResultsService(this);
        respawnPull = new top.cheesesmp.duelcore.ui.RespawnPull(this);
        animations = new top.cheesesmp.duelcore.ui.Animations(this);
        dialogs = new DialogService(this);
        leaderboards = new LeaderboardService(this, database);
        arenas = new ArenaManager(this);
        editor = new ArenaEditor(this, arenas);
        commands = new CommandService(this);
        diagnostics = new Diagnostics(this);
        parties = new top.cheesesmp.duelcore.party.PartyService(this);

        arenas.enable();
        hub.enable();

        var pm = getServer().getPluginManager();
        pm.registerEvents(profiles, this);
        pm.registerEvents(new HubListener(this), this);
        pm.registerEvents(queue, this);
        pm.registerEvents(new MatchListener(this), this);
        pm.registerEvents(spectate, this);
        pm.registerEvents(duels, this);
        pm.registerEvents(sidebar, this);
        pm.registerEvents(tags, this);
        pm.registerEvents(new top.cheesesmp.duelcore.chat.ChatFilterListener(this), this);
        clicks = new ClickRouter(this);
        pm.registerEvents(clicks, this);
        pm.registerEvents(results, this);
        pm.registerEvents(respawnPull, this);
        pm.registerEvents(new top.cheesesmp.duelcore.ui.MotdService(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> commands.register(event.registrar()));

        var scheduler = getServer().getScheduler();
        scheduler.runTaskTimer(this, matches, 1L, 1L);
        scheduler.runTaskTimer(this, arenas.queue(), 1L, 1L);
        scheduler.runTaskTimer(this, queue, 20L, cfg.mmIntervalTicks);
        scheduler.runTaskTimer(this, sidebar, 20L, 20L);
        scheduler.runTaskTimer(this, tags, 40L, 40L);
        scheduler.runTaskTimer(this, duels, 20L, 20L);
        scheduler.runTaskTimer(this, leaderboards, 200L, 200L);
        scheduler.runTaskTimer(this, profiles::sweep, 1200L, 1200L);
        parties.enable(); // listeners, "party" clicks, hub item and timers; loads the parties once the database is ready

        getServer().getServicesManager().register(DuelCoreApi.class, new DuelCoreApi(this), this, ServicePriority.Normal);

        if (pm.isPluginEnabled("PlaceholderAPI")) {
            try {
                papiHooked = top.cheesesmp.duelcore.hook.PapiHook.register(this);
                if (papiHooked) getLogger().info("Hooked into PlaceholderAPI (%duelcore_...%)");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "PlaceholderAPI hook failed", t);
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            profiles.loadOnline(p).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline()) hub.send(p);
            }));
        }
        getLogger().info("DuelCore " + getPluginMeta().getVersion() + " enabled in " + (System.currentTimeMillis() - start)
            + " ms (" + kits.size() + " kits, " + arenas.templates().size() + " arenas, " + cfg.dbType + ")");
    }

    @Override
    public void onDisable() {
        try {
            if (respawnPull != null) respawnPull.cancelAll();
            if (matches != null) matches.cancelAll();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Cancelling matches failed", t);
        }
        if (parties != null) parties.disable();
        if (papiHooked) {
            try {
                top.cheesesmp.duelcore.hook.PapiHook.unregister();
            } catch (Throwable ignored) {
                // PAPI already gone
            }
        }
        if (arenas != null) arenas.disable();
        if (database != null) database.close();
    }

    /** Reloads configs, kits, arenas and tiers. Running matches keep their kit. Returns problems found. */
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        config.load();
        problems.addAll(kits.load());
        profiles.syncKits(kits.ids());
        problems.addAll(arenas.loadTemplates());
        arenas.queue().budget(settings().blockBudgetMs);
        queue.reload();
        ratingSystem = buildRatingSystem();
        leaderboards.clear();
        tags.refreshTeams();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (matches.match(p.getUniqueId()) == null && spectate.spectating(p.getUniqueId()) == null) hub.giveItems(p);
            sidebar.refresh(p);
        }
        return problems;
    }

    private RatingSystem buildRatingSystem() {
        MainConfig c = settings();
        if ("glicko2".equals(c.ratingSystem)) {
            return new Glicko2Rating(c.glickoTau, c.ratingDefault, c.glickoMinRd, c.glickoDefaultRd, c.ratingFloor);
        }
        return new EloRating(c.eloK, c.eloProvisionalK, tiers().placementMatches(), c.ratingFloor);
    }

    // ------------------------------------------------------------------ accessors

    public MainConfig settings() {
        return config.main();
    }

    public Messages messages() {
        return config.messages();
    }

    public TierService tiers() {
        return config.tiers();
    }

    public GuiConfig gui() {
        return config.gui();
    }

    public Database database() {
        return database;
    }

    public KitManager kits() {
        return kits;
    }

    public ProfileService profiles() {
        return profiles;
    }

    public RatingSystem ratingSystem() {
        return ratingSystem;
    }

    public ArenaManager arenas() {
        return arenas;
    }

    public ArenaEditor editor() {
        return editor;
    }

    public HubService hub() {
        return hub;
    }

    public VisibilityService visibility() {
        return visibility;
    }

    public SidebarService sidebar() {
        return sidebar;
    }

    public top.cheesesmp.duelcore.chat.ChatFilter chatFilter() {
        return config.chatFilter();
    }

    public TagService tags() {
        return tags;
    }

    public QueueService queue() {
        return queue;
    }

    public MatchService matches() {
        return matches;
    }

    public SpectateService spectate() {
        return spectate;
    }

    public DuelRequestService duels() {
        return duels;
    }

    public ResultsService results() {
        return results;
    }

    public top.cheesesmp.duelcore.ui.RespawnPull respawnPull() {
        return respawnPull;
    }

    public top.cheesesmp.duelcore.ui.Animations animations() {
        return animations;
    }

    /** Custom-click routing; features register their own {@code duelcore:<prefix>/…} handlers here. */
    public ClickRouter clicks() {
        return clicks;
    }

    public DialogService dialogs() {
        return dialogs;
    }

    public LeaderboardService leaderboards() {
        return leaderboards;
    }

    public CommandService commands() {
        return commands;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    /** Persistent parties: /party, party chat, party matches. */
    public top.cheesesmp.duelcore.party.PartyService parties() {
        return parties;
    }
}
