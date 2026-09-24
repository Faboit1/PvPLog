package top.cheesesmp.duelcore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.arena.ArenaEditor;
import top.cheesesmp.duelcore.arena.ArenaTemplate;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.ProfileService;

/** /duelcore — administration. Every branch has its own permission node under duelcore.admin. */
final class AdminCommand {

    private final DuelCorePlugin plugin;
    private final CommandService cmd;

    AdminCommand(DuelCorePlugin plugin, CommandService cmd) {
        this.plugin = plugin;
        this.cmd = cmd;
    }

    private void send(CommandSender sender, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        plugin.messages().send(sender, key, r);
    }

    LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("duelcore")
            .requires(src -> src.getSender().hasPermission("duelcore.admin") || anyAdminChild(src.getSender()))
            .executes(ctx -> {
                send(ctx.getSource().getSender(), "admin.help", Messages.text("version", plugin.getPluginMeta().getVersion()));
                return Command.SINGLE_SUCCESS;
            });

        root.then(Commands.literal("reload").requires(CommandService.perm("duelcore.admin.reload")).executes(ctx -> {
            List<String> problems = plugin.reload();
            send(ctx.getSource().getSender(), "admin.reloaded", Messages.num("kits", plugin.kits().size()),
                Messages.num("arenas", plugin.arenas().templates().size()), Messages.num("problems", problems.size()));
            for (String p : problems) ctx.getSource().getSender().sendMessage(" - " + p);
            return Command.SINGLE_SUCCESS;
        }));

        root.then(Commands.literal("sethub").requires(CommandService.perm("duelcore.admin.sethub")).executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p == null) return 0;
            plugin.hub().setSpawn(p.getLocation());
            send(p, "admin.hub-set");
            return Command.SINGLE_SUCCESS;
        }));

        root.then(arena());
        root.then(kit());
        root.then(season());
        root.then(rating());
        root.then(Commands.literal("debug").requires(CommandService.perm("duelcore.admin.debug"))
            .executes(ctx -> debug(ctx, false))
            .then(Commands.literal("gc").executes(ctx -> debug(ctx, true)))
            .then(Commands.literal("trace").executes(ctx -> {
                new top.cheesesmp.duelcore.debug.DamageTracer(plugin).start(15);
                ctx.getSource().getSender().sendMessage("Tracing player damage for 15 seconds (see console).");
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("matches").executes(ctx -> {
                for (String line : plugin.diagnostics().matches()) ctx.getSource().getSender().sendMessage(line);
                return Command.SINGLE_SUCCESS;
            })));
        root.then(Commands.literal("forceend").requires(CommandService.perm("duelcore.admin.match"))
            .then(Commands.argument("player", StringArgumentType.word()).suggests(cmd.onlineNames()).executes(ctx -> {
                Player target = Bukkit.getPlayerExact(StringArgumentType.getString(ctx, "player"));
                Match m = target == null ? null : plugin.matches().match(target.getUniqueId());
                if (m == null) {
                    send(ctx.getSource().getSender(), "admin.not-in-match");
                    return 0;
                }
                plugin.matches().end(m, -1, Match.EndReason.ADMIN);
                send(ctx.getSource().getSender(), "admin.match-ended", Messages.num("id", m.id()));
                return Command.SINGLE_SUCCESS;
            })));
        return root.build();
    }

    private static boolean anyAdminChild(CommandSender sender) {
        for (String child : List.of("reload", "sethub", "arena", "kit", "season", "rating", "debug", "match")) {
            if (sender.hasPermission("duelcore.admin." + child)) return true;
        }
        return false;
    }

    private int debug(CommandContext<CommandSourceStack> ctx, boolean gc) {
        CommandSender sender = ctx.getSource().getSender();
        for (String line : plugin.diagnostics().report(gc)) {
            sender.sendMessage(line);
            if (!(sender instanceof Player)) continue;
        }
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            for (String line : plugin.diagnostics().report(false)) plugin.getLogger().info("[debug] " + line);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ arena

    private LiteralArgumentBuilder<CommandSourceStack> arena() {
        ArenaEditor editor = plugin.editor();
        LiteralArgumentBuilder<CommandSourceStack> a = Commands.literal("arena").requires(CommandService.perm("duelcore.admin.arena"));
        a.then(Commands.literal("world").executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p == null) return 0;
            p.teleportAsync(new Location(editor.editorWorld(), 0.5, 100, 0.5));
            send(p, "arena.editor-world");
            return Command.SINGLE_SUCCESS;
        }));
        a.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p == null) return 0;
            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_]{1,32}")) {
                send(p, "arena.bad-name");
                return 0;
            }
            editor.start(p, name);
            send(p, "arena.created", Messages.text("name", name));
            return Command.SINGLE_SUCCESS;
        })));
        a.then(pos("pos1", (s, l) -> s.pos1 = l.toBlockLocation()));
        a.then(pos("pos2", (s, l) -> s.pos2 = l.toBlockLocation()));
        a.then(pos("setspawn1", (s, l) -> s.spawn1 = l.clone()));
        a.then(pos("setspawn2", (s, l) -> s.spawn2 = l.clone()));
        a.then(Commands.literal("tags").then(Commands.argument("tags", StringArgumentType.greedyString()).executes(ctx -> {
            Player p = CommandService.player(ctx);
            ArenaEditor.Session s = p == null ? null : editor.session(p);
            if (s == null) {
                if (p != null) send(p, "arena.no-session");
                return 0;
            }
            s.tags = new ArrayList<>(Arrays.stream(StringArgumentType.getString(ctx, "tags").toLowerCase(Locale.ROOT).split("[ ,]+"))
                .filter(t -> !t.isBlank()).toList());
            send(p, "arena.tags-set", Messages.text("tags", String.join(", ", s.tags)));
            return Command.SINGLE_SUCCESS;
        })));
        a.then(Commands.literal("buildheight").then(Commands.argument("blocks", IntegerArgumentType.integer(0, 128)).executes(ctx -> {
            Player p = CommandService.player(ctx);
            ArenaEditor.Session s = p == null ? null : editor.session(p);
            if (s == null) {
                if (p != null) send(p, "arena.no-session");
                return 0;
            }
            s.buildHeight = IntegerArgumentType.getInteger(ctx, "blocks");
            send(p, "arena.buildheight-set", Messages.num("blocks", s.buildHeight));
            return Command.SINGLE_SUCCESS;
        })));
        a.then(Commands.literal("displayname").then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
            Player p = CommandService.player(ctx);
            ArenaEditor.Session s = p == null ? null : editor.session(p);
            if (s == null) {
                if (p != null) send(p, "arena.no-session");
                return 0;
            }
            s.displayName = StringArgumentType.getString(ctx, "name");
            send(p, "arena.displayname-set", Messages.text("name", s.displayName));
            return Command.SINGLE_SUCCESS;
        })));
        a.then(Commands.literal("save").executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p == null) return 0;
            editor.save(p).whenComplete((name, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    send(p, "arena.save-failed", Messages.text("reason", String.valueOf(cause.getMessage())));
                } else {
                    send(p, "arena.saved", Messages.text("name", name));
                }
            }));
            return Command.SINGLE_SUCCESS;
        }));
        a.then(Commands.literal("cancel").executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p != null) {
                editor.cancel(p);
                send(p, "arena.cancelled");
            }
            return Command.SINGLE_SUCCESS;
        }));
        a.then(Commands.literal("edit").then(Commands.argument("name", StringArgumentType.word()).suggests(arenaNames()).executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p == null) return 0;
            ArenaTemplate t = plugin.arenas().template(StringArgumentType.getString(ctx, "name"));
            if (t == null) {
                send(p, "arena.unknown");
                return 0;
            }
            editor.edit(p, t).whenComplete((s, err) -> Bukkit.getScheduler().runTask(plugin, () ->
                send(p, err == null ? "arena.editing" : "arena.save-failed", Messages.text("name", t.name()),
                    Messages.text("reason", err == null ? "" : String.valueOf(err.getMessage())))));
            return Command.SINGLE_SUCCESS;
        })));
        a.then(Commands.literal("import").then(Commands.argument("name", StringArgumentType.word())
            .then(Commands.argument("file", StringArgumentType.string()).suggests((ctx, b) -> {
                File[] files = new File(plugin.getDataFolder(), "schematics").listFiles((d, n) -> n.endsWith(".schem"));
                if (files != null) for (File f : files) b.suggest(f.getName());
                return b.buildFuture();
            }).executes(ctx -> {
                Player p = CommandService.player(ctx);
                if (p == null) return 0;
                String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_]{1,32}")) {
                    send(p, "arena.bad-name");
                    return 0;
                }
                editor.importSchematic(p, name, StringArgumentType.getString(ctx, "file")).whenComplete((s, err) ->
                    Bukkit.getScheduler().runTask(plugin, () -> send(p, err == null ? "arena.imported" : "arena.save-failed",
                        Messages.text("name", name), Messages.text("reason", err == null ? "" : String.valueOf(err.getMessage())))));
                return Command.SINGLE_SUCCESS;
            }))));
        a.then(Commands.literal("list").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            for (ArenaTemplate t : plugin.arenas().templates()) {
                send(sender, "arena.list-line", Messages.text("name", t.name()), Messages.text("display", t.displayName()),
                    Messages.text("tags", String.join(", ", t.tags())), Messages.text("size", t.sizeX() + "×" + t.sizeY() + "×" + t.sizeZ()),
                    Messages.text("state", t.enabled() ? "on" : "off"));
            }
            return Command.SINGLE_SUCCESS;
        }));
        a.then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).suggests(arenaNames()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
            File dir = plugin.arenas().arenaFolder();
            boolean deleted = new File(dir, name + ".yml").delete();
            new File(dir, name + ".dca").delete();
            plugin.arenas().loadTemplates();
            send(ctx.getSource().getSender(), deleted ? "arena.deleted" : "arena.unknown", Messages.text("name", name));
            return Command.SINGLE_SUCCESS;
        })));
        return a;
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> arenaNames() {
        return (ctx, b) -> {
            for (ArenaTemplate t : plugin.arenas().templates()) b.suggest(t.name());
            return b.buildFuture();
        };
    }

    private LiteralArgumentBuilder<CommandSourceStack> pos(String name, java.util.function.BiConsumer<ArenaEditor.Session, Location> setter) {
        return Commands.literal(name).executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p == null) return 0;
            ArenaEditor.Session s = plugin.editor().session(p);
            if (s == null) {
                send(p, "arena.no-session");
                return 0;
            }
            setter.accept(s, p.getLocation());
            send(p, "arena.point-set", Messages.text("point", name), Messages.text("x", String.valueOf(p.getLocation().getBlockX())),
                Messages.text("y", String.valueOf(p.getLocation().getBlockY())), Messages.text("z", String.valueOf(p.getLocation().getBlockZ())));
            return Command.SINGLE_SUCCESS;
        });
    }

    // ------------------------------------------------------------------ kit

    private LiteralArgumentBuilder<CommandSourceStack> kit() {
        LiteralArgumentBuilder<CommandSourceStack> k = Commands.literal("kit").requires(CommandService.perm("duelcore.admin.kit"));
        k.then(Commands.literal("list").executes(ctx -> {
            for (Kit kit : plugin.kits().all()) {
                send(ctx.getSource().getSender(), "kit.list-line", Messages.comp("kit_icon", kit.sprite()),
                    Messages.comp("kit", kit.displayName()), Messages.text("id", kit.id()),
                    Messages.num("first_to", kit.firstTo()), Messages.text("state", kit.enabled() ? "on" : "off"));
            }
            return Command.SINGLE_SUCCESS;
        }));
        k.then(Commands.literal("give").then(Commands.argument("kit", StringArgumentType.word()).suggests(cmd.kitSuggestions()).executes(ctx -> {
            Player p = CommandService.player(ctx);
            Kit kit = plugin.kits().get(StringArgumentType.getString(ctx, "kit"));
            if (p == null || kit == null) return 0;
            if (plugin.matches().match(p.getUniqueId()) != null) return 0;
            KitManager.apply(p, kit);
            p.setGameMode(org.bukkit.GameMode.CREATIVE);
            send(p, "kit.given", Messages.comp("kit", kit.displayName()));
            return Command.SINGLE_SUCCESS;
        })));
        k.then(Commands.literal("save").then(Commands.argument("kit", StringArgumentType.word()).suggests(cmd.kitSuggestions()).executes(ctx -> {
            Player p = CommandService.player(ctx);
            if (p == null) return 0;
            String id = StringArgumentType.getString(ctx, "kit").toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_]{1,32}")) return 0;
            try {
                plugin.kits().saveFromInventory(id, p);
                plugin.kits().load();
                plugin.profiles().syncKits(plugin.kits().ids());
                send(p, "kit.saved", Messages.text("id", id));
            } catch (Exception e) {
                send(p, "kit.save-failed", Messages.text("reason", String.valueOf(e.getMessage())));
            }
            return Command.SINGLE_SUCCESS;
        })));
        return k;
    }

    // ------------------------------------------------------------------ season

    private LiteralArgumentBuilder<CommandSourceStack> season() {
        LiteralArgumentBuilder<CommandSourceStack> s = Commands.literal("season").requires(CommandService.perm("duelcore.admin.season"));
        s.then(Commands.literal("info").executes(ctx -> {
            var season = plugin.profiles().season();
            send(ctx.getSource().getSender(), "season.info", Messages.num("id", season.id()), Messages.text("name", season.name()));
            return Command.SINGLE_SUCCESS;
        }));
        s.then(Commands.literal("reset").then(Commands.argument("name", StringArgumentType.string())
            .executes(ctx -> {
                send(ctx.getSource().getSender(), "season.confirm", Messages.text("name", StringArgumentType.getString(ctx, "name")));
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.literal("confirm").executes(ctx -> {
                CommandSender sender = ctx.getSource().getSender();
                String name = StringArgumentType.getString(ctx, "name");
                plugin.matches().cancelAll();
                plugin.profiles().startNewSeason(name)
                    .thenCompose(season -> plugin.profiles().reloadOnline().thenApply(v -> season))
                    .whenComplete((season, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (err != null) {
                            send(sender, "season.failed", Messages.text("reason", String.valueOf(err.getMessage())));
                            return;
                        }
                        plugin.leaderboards().clear();
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            plugin.tags().update(p);
                            plugin.sidebar().refresh(p);
                        }
                        send(sender, "season.started", Messages.num("id", season.id()), Messages.text("name", season.name()));
                        plugin.getServer().broadcast(plugin.messages().get("season.broadcast", Messages.text("name", season.name())));
                    }));
                return Command.SINGLE_SUCCESS;
            }))));
        s.then(Commands.literal("recalc").executes(ctx -> recalc(ctx.getSource().getSender())));
        return s;
    }

    private int recalc(CommandSender sender) {
        plugin.profiles().recalcStandings().thenCompose(n -> plugin.profiles().reloadOnline().thenApply(v -> n))
            .whenComplete((n, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    send(sender, "season.failed", Messages.text("reason", String.valueOf(err.getMessage())));
                    return;
                }
                plugin.leaderboards().clear();
                for (Player p : Bukkit.getOnlinePlayers()) plugin.tags().update(p);
                send(sender, "season.recalculated", Messages.num("players", n));
            }));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ rating / region

    private LiteralArgumentBuilder<CommandSourceStack> rating() {
        LiteralArgumentBuilder<CommandSourceStack> r = Commands.literal("player").requires(CommandService.perm("duelcore.admin.rating"));
        r.then(Commands.argument("player", StringArgumentType.word()).suggests(cmd.onlineNames())
            .then(Commands.literal("setrating").then(Commands.argument("kit", StringArgumentType.word()).suggests(cmd.kitSuggestions())
                .then(Commands.argument("rating", DoubleArgumentType.doubleArg(0, 10000)).executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Kit kit = plugin.kits().get(StringArgumentType.getString(ctx, "kit"));
                    if (kit == null) return 0;
                    double rating = DoubleArgumentType.getDouble(ctx, "rating");
                    withProfile(sender, StringArgumentType.getString(ctx, "player"), p -> {
                        KitStats st = plugin.profiles().stats(p, kit.id());
                        st.rating = rating;
                        st.peak = Math.max(st.peak, rating);
                        st.updatedAt = System.currentTimeMillis();
                        plugin.tiers().refresh(p);
                        plugin.profiles().persistRating(p.uuid(), new ProfileService.RatingWrite(p.id(), kit.id(), st.snapshot(),
                            p.elo(), p.overall()));
                        plugin.leaderboards().invalidate(kit.id());
                        send(sender, "admin.rating-set", Messages.text("player", p.name()), Messages.comp("kit", kit.displayName()),
                            Messages.num("rating", (int) rating));
                    });
                    return Command.SINGLE_SUCCESS;
                }))))
            .then(Commands.literal("setgames").then(Commands.argument("kit", StringArgumentType.word()).suggests(cmd.kitSuggestions())
                .then(Commands.argument("games", IntegerArgumentType.integer(0, 100000)).executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Kit kit = plugin.kits().get(StringArgumentType.getString(ctx, "kit"));
                    if (kit == null) return 0;
                    int games = IntegerArgumentType.getInteger(ctx, "games");
                    withProfile(sender, StringArgumentType.getString(ctx, "player"), p -> {
                        KitStats st = plugin.profiles().stats(p, kit.id());
                        st.games = games;
                        st.updatedAt = System.currentTimeMillis();
                        plugin.tiers().refresh(p);
                        plugin.profiles().persistRating(p.uuid(), new ProfileService.RatingWrite(p.id(), kit.id(), st.snapshot(),
                            p.elo(), p.overall()));
                        send(sender, "admin.games-set", Messages.text("player", p.name()), Messages.num("games", games));
                    });
                    return Command.SINGLE_SUCCESS;
                }))))
            .then(Commands.literal("setregion").then(Commands.argument("region", StringArgumentType.word()).executes(ctx -> {
                CommandSender sender = ctx.getSource().getSender();
                String region = StringArgumentType.getString(ctx, "region").toUpperCase(Locale.ROOT);
                withProfile(sender, StringArgumentType.getString(ctx, "player"), p -> {
                    p.region(plugin.settings().regions.contains(region) ? region : null);
                    plugin.profiles().saveSettings(p);
                    send(sender, "admin.region-set", Messages.text("player", p.name()),
                        Messages.text("region", p.region() == null ? "—" : p.region()));
                });
                return Command.SINGLE_SUCCESS;
            })))
            .then(Commands.literal("setcountry").then(Commands.argument("country", StringArgumentType.word()).executes(ctx -> {
                CommandSender sender = ctx.getSource().getSender();
                String cc = StringArgumentType.getString(ctx, "country").toUpperCase(Locale.ROOT);
                withProfile(sender, StringArgumentType.getString(ctx, "player"), p -> {
                    p.country(cc.matches("[A-Z]{2}") ? cc : null);
                    plugin.profiles().saveSettings(p);
                    send(sender, "admin.country-set", Messages.text("player", p.name()),
                        Messages.text("country", p.country() == null ? "—" : p.country()));
                });
                return Command.SINGLE_SUCCESS;
            }))));
        return r;
    }

    private void withProfile(CommandSender sender, String name, java.util.function.Consumer<PlayerProfile> action) {
        plugin.profiles().lookup(name, null).thenAccept(o -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (o.isEmpty()) {
                send(sender, "profile.not-found", Messages.text("player", name));
                return;
            }
            action.accept(o.get());
            Player online = Bukkit.getPlayer(o.get().uuid());
            if (online != null) {
                plugin.tags().update(online);
                plugin.sidebar().refresh(online);
            }
        }));
    }
}
