package top.cheesesmp.duelcore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.DuelRequestService;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.SpectateService;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.ProfileService;
import top.cheesesmp.duelcore.queue.QueueMode;
import top.cheesesmp.duelcore.queue.QueueService;
import top.cheesesmp.duelcore.rating.Tier;

/** Player commands (Brigadier) and the actions shared with dialog clicks. */
public final class CommandService {

    private final DuelCorePlugin plugin;
    private final Map<UUID, Long> leaveConfirm = new HashMap<>();

    public CommandService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        commands.register(queue(), "Pick a kit and join a queue", List.of("play", "q"));
        commands.register(leave(), "Leave the queue, stop spectating or forfeit", List.of("forfeit"));
        commands.register(profile(), "Show a duel profile", List.of("stats"));
        commands.register(leaderboard(), "Show leaderboards", List.of("lb", "top"));
        commands.register(spectate(), "Watch a live match", List.of("spec"));
        commands.register(duel(), "Challenge a player (unranked)", List.of());
        commands.register(Commands.literal("settings").requires(perm("duelcore.settings"))
            .executes(ctx -> {
                Player p = player(ctx);
                if (p != null) plugin.dialogs().settings(p);
                return Command.SINGLE_SUCCESS;
            }).build(), "Duel settings", List.of());
        commands.register(tier(), "Tier management", List.of());
        new top.cheesesmp.duelcore.friends.FriendCommands(plugin, plugin.friends()).register(commands);
        commands.register(new AdminCommand(plugin, this).build(), "DuelCore administration", List.of("dc"));
    }

    // ------------------------------------------------------------------ helpers

    public static Predicate<CommandSourceStack> perm(String node) {
        return src -> src.getSender().hasPermission(node);
    }

    static @Nullable Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) return p;
        if (ctx.getSource().getSender() instanceof Player p) return p;
        ctx.getSource().getSender().sendMessage("This command needs a player.");
        return null;
    }

    static Player target(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return ctx.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    }

    public SuggestionProvider<CommandSourceStack> kitSuggestions() {
        return (ctx, builder) -> {
            String rem = builder.getRemainingLowerCase();
            for (Kit k : plugin.kits().enabled()) if (k.id().startsWith(rem)) builder.suggest(k.id());
            return builder.buildFuture();
        };
    }

    SuggestionProvider<CommandSourceStack> onlineNames() {
        return (ctx, builder) -> {
            String rem = builder.getRemainingLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(p.getName());
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<CommandSourceStack> tierSuggestions() {
        return (ctx, builder) -> {
            String rem = builder.getRemainingLowerCase();
            for (Tier t : Tier.values()) if (t.name().toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(t.name());
            return builder.buildFuture();
        };
    }

    private @Nullable Kit kit(CommandSender sender, String id) {
        Kit kit = plugin.kits().get(id);
        if (kit == null || !kit.enabled()) {
            plugin.messages().send(sender, "command.unknown-kit", Messages.text("kit", id));
            return null;
        }
        return kit;
    }

    // ------------------------------------------------------------------ shared actions

    public void joinQueue(Player player, Kit kit, QueueMode mode) {
        QueueService.JoinResult r = plugin.queue().join(player, kit, mode);
        switch (r) {
            case OK, SWITCHED -> {
                player.closeDialog();
                plugin.messages().send(player, r == QueueService.JoinResult.OK ? "queue.joined" : "queue.switched",
                    Messages.comp("kit", kit.displayName()), Messages.comp("kit_icon", kit.sprite()),
                    Messages.text("mode", plugin.messages().raw("mode." + mode.id())));
            }
            default -> plugin.messages().send(player, "queue.result." + r.name().toLowerCase(Locale.ROOT),
                Messages.comp("kit", kit.displayName()));
        }
    }

    public void openProfile(Player viewer, String name, boolean legacy) {
        if (!name.equalsIgnoreCase(viewer.getName()) && !viewer.hasPermission("duelcore.profile.others")) {
            plugin.messages().send(viewer, "command.no-permission");
            return;
        }
        if (legacy) {
            plugin.profiles().previousSeason().thenAccept(season -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (season == null) {
                    plugin.messages().send(viewer, "profile.no-legacy");
                    return;
                }
                lookupAndShow(viewer, name, season.id(), true);
            }));
            return;
        }
        lookupAndShow(viewer, name, null, false);
    }

    private void lookupAndShow(Player viewer, String name, @Nullable Integer season, boolean legacy) {
        plugin.profiles().lookup(name, season).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (result.isEmpty()) {
                plugin.messages().send(viewer, "profile.not-found", Messages.text("player", name));
                return;
            }
            plugin.dialogs().profile(viewer, result.get(), legacy);
        }));
    }

    public void sendDuel(Player from, Player to, Kit kit) {
        DuelRequestService.Result r = plugin.duels().send(from, to, kit);
        if (r != DuelRequestService.Result.SENT) {
            plugin.messages().send(from, "duel.result." + r.name().toLowerCase(Locale.ROOT), Messages.text("player", to.getName()));
        }
        from.closeDialog();
    }

    public void acceptDuel(Player player, UUID from) {
        DuelRequestService.Result r = plugin.duels().accept(player, from);
        if (r != DuelRequestService.Result.ACCEPTED) {
            plugin.messages().send(player, "duel.result." + r.name().toLowerCase(Locale.ROOT), Messages.text("player", ""));
        }
    }

    // ------------------------------------------------------------------ /queue

    private LiteralCommandNode<CommandSourceStack> queue() {
        return Commands.literal("queue").requires(perm("duelcore.queue"))
            .executes(ctx -> {
                Player p = player(ctx);
                if (p != null) plugin.dialogs().queue(p, QueueMode.RANKED, false);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.argument("kit", StringArgumentType.word()).suggests(kitSuggestions())
                .executes(ctx -> queueCmd(ctx, QueueMode.RANKED))
                .then(Commands.literal("ranked").executes(ctx -> queueCmd(ctx, QueueMode.RANKED)))
                .then(Commands.literal("unranked").executes(ctx -> queueCmd(ctx, QueueMode.UNRANKED))))
            .build();
    }

    private int queueCmd(CommandContext<CommandSourceStack> ctx, QueueMode mode) {
        Player p = player(ctx);
        if (p == null) return 0;
        Kit kit = kit(p, StringArgumentType.getString(ctx, "kit"));
        if (kit != null) joinQueue(p, kit, mode);
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /leave

    private LiteralCommandNode<CommandSourceStack> leave() {
        return Commands.literal("leave").requires(perm("duelcore.leave"))
            .executes(ctx -> {
                Player p = player(ctx);
                if (p == null) return 0;
                Match m = plugin.matches().match(p.getUniqueId());
                if (m != null) {
                    long now = System.currentTimeMillis();
                    Long first = leaveConfirm.remove(p.getUniqueId());
                    if (first == null || now - first > 5000) {
                        leaveConfirm.put(p.getUniqueId(), now);
                        plugin.messages().send(p, "match.forfeit-confirm");
                        return Command.SINGLE_SUCCESS;
                    }
                    plugin.messages().send(p, "match.forfeited");
                    plugin.matches().forfeit(p, false);
                    return Command.SINGLE_SUCCESS;
                }
                if (plugin.spectate().leave(p, true)) return Command.SINGLE_SUCCESS;
                plugin.queue().leave(p, true);
                return Command.SINGLE_SUCCESS;
            }).build();
    }

    // ------------------------------------------------------------------ /profile

    private LiteralCommandNode<CommandSourceStack> profile() {
        return Commands.literal("profile").requires(perm("duelcore.profile"))
            .executes(ctx -> {
                Player p = player(ctx);
                if (p != null) openProfile(p, p.getName(), false);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                .executes(ctx -> {
                    Player p = player(ctx);
                    if (p != null) openProfile(p, StringArgumentType.getString(ctx, "player"), false);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("legacy").executes(ctx -> {
                    Player p = player(ctx);
                    if (p != null) openProfile(p, StringArgumentType.getString(ctx, "player"), true);
                    return Command.SINGLE_SUCCESS;
                })))
            .build();
    }

    // ------------------------------------------------------------------ /leaderboard

    private LiteralCommandNode<CommandSourceStack> leaderboard() {
        return Commands.literal("leaderboard").requires(perm("duelcore.leaderboard"))
            .executes(ctx -> lb(ctx, "overall", null))
            .then(Commands.argument("category", StringArgumentType.word())
                .suggests((ctx, b) -> {
                    b.suggest("overall");
                    return kitSuggestions().getSuggestions(ctx, b);
                })
                .executes(ctx -> lb(ctx, StringArgumentType.getString(ctx, "category"), null))
                .then(Commands.argument("region", StringArgumentType.word())
                    .suggests((ctx, b) -> {
                        plugin.settings().regions.forEach(b::suggest);
                        return b.buildFuture();
                    })
                    .executes(ctx -> lb(ctx, StringArgumentType.getString(ctx, "category"),
                        StringArgumentType.getString(ctx, "region")))))
            .build();
    }

    private int lb(CommandContext<CommandSourceStack> ctx, String category, @Nullable String region) {
        CommandSender sender = ctx.getSource().getSender();
        String reg = region == null ? null : region.toUpperCase(Locale.ROOT);
        if (reg != null && !plugin.settings().regions.contains(reg)) reg = null;
        if (sender instanceof Player p) {
            plugin.dialogs().leaderboard(p, category, reg);
        } else {
            String cat = category.toLowerCase(Locale.ROOT);
            plugin.leaderboards().get(cat, reg, null).thenAccept(rows -> Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage("Leaderboard " + cat + (rows.isEmpty() ? ": empty" : ":"));
                rows.stream().limit(10).forEach(r -> sender.sendMessage(" #" + r.rank() + " " + r.name() + " "
                    + Math.round(r.value()) + " " + (r.tier() == null ? "" : r.tier().name()) + " " + r.wins() + "W/" + r.losses() + "L"));
            }));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /spectate

    private LiteralCommandNode<CommandSourceStack> spectate() {
        return Commands.literal("spectate").requires(perm("duelcore.spectate"))
            .executes(ctx -> {
                Player p = player(ctx);
                if (p != null) plugin.dialogs().spectate(p);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.literal("stop").executes(ctx -> {
                Player p = player(ctx);
                if (p != null && !plugin.spectate().leave(p, true)) plugin.messages().send(p, "spectate.not-spectating");
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.argument("player", ArgumentTypes.player()).executes(ctx -> {
                Player p = player(ctx);
                if (p == null) return 0;
                Player target = target(ctx, "player");
                SpectateService.Result r = plugin.spectate().spectate(p, target);
                if (r != SpectateService.Result.OK) {
                    plugin.messages().send(p, "spectate.result." + r.name().toLowerCase(Locale.ROOT),
                        Messages.text("player", target.getName()));
                }
                return Command.SINGLE_SUCCESS;
            }))
            .build();
    }

    // ------------------------------------------------------------------ /duel

    private LiteralCommandNode<CommandSourceStack> duel() {
        return Commands.literal("duel").requires(perm("duelcore.duel"))
            .executes(ctx -> {
                Player p = player(ctx);
                if (p != null) plugin.dialogs().duelPlayers(p);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.literal("accept")
                .executes(ctx -> duelAnswer(ctx, null, true))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                    .executes(ctx -> duelAnswer(ctx, StringArgumentType.getString(ctx, "player"), true))))
            .then(Commands.literal("deny")
                .executes(ctx -> duelAnswer(ctx, null, false))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                    .executes(ctx -> duelAnswer(ctx, StringArgumentType.getString(ctx, "player"), false))))
            .then(Commands.argument("player", ArgumentTypes.player())
                .executes(ctx -> {
                    Player p = player(ctx);
                    if (p == null) return 0;
                    Player target = target(ctx, "player");
                    if (target.equals(p)) {
                        plugin.messages().send(p, "duel.result.self");
                        return 0;
                    }
                    plugin.dialogs().duelPicker(p, target);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("kit", StringArgumentType.word()).suggests(kitSuggestions())
                    .executes(ctx -> {
                        Player p = player(ctx);
                        if (p == null) return 0;
                        Player target = target(ctx, "player");
                        Kit kit = kit(p, StringArgumentType.getString(ctx, "kit"));
                        if (kit != null) sendDuel(p, target, kit);
                        return Command.SINGLE_SUCCESS;
                    })))
            .build();
    }

    private int duelAnswer(CommandContext<CommandSourceStack> ctx, @Nullable String from, boolean accept) {
        Player p = player(ctx);
        if (p == null) return 0;
        DuelRequestService.Request r = plugin.duels().find(p.getUniqueId(), from);
        if (r == null) {
            plugin.messages().send(p, "duel.result.no_request");
            return 0;
        }
        if (accept) acceptDuel(p, r.from());
        else plugin.duels().deny(p, r.from());
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /tier

    private LiteralCommandNode<CommandSourceStack> tier() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tier").requires(perm("duelcore.tier"));
        root.then(Commands.literal("set")
            .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                .then(Commands.argument("kit", StringArgumentType.word()).suggests(kitSuggestions())
                    .then(Commands.argument("tier", StringArgumentType.word()).suggests(tierSuggestions())
                        .executes(ctx -> {
                            Tier t = Tier.parse(StringArgumentType.getString(ctx, "tier"));
                            if (t == null) {
                                plugin.messages().send(ctx.getSource().getSender(), "tier.unknown");
                                return 0;
                            }
                            return setTier(ctx, t);
                        })))));
        root.then(Commands.literal("clear")
            .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                .then(Commands.argument("kit", StringArgumentType.word()).suggests(kitSuggestions())
                    .executes(ctx -> setTier(ctx, null)))));
        root.then(Commands.literal("info")
            .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    String name = StringArgumentType.getString(ctx, "player");
                    plugin.profiles().lookup(name, null).thenAccept(o -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (o.isEmpty()) {
                            plugin.messages().send(sender, "profile.not-found", Messages.text("player", name));
                            return;
                        }
                        PlayerProfile p = o.get();
                        plugin.messages().send(sender, "tier.info-header", Messages.text("player", p.name()),
                            Messages.comp("tier", plugin.tiers().format(p.overall())),
                            Messages.text("elo", top.cheesesmp.duelcore.rating.TierService.eloText(p)));
                        p.allStats().forEach((kit, s) -> plugin.messages().send(sender, "tier.info-line",
                            Messages.text("kit", kit), Messages.comp("tier", plugin.tiers().format(plugin.tiers().kitTier(kit, s))),
                            Messages.num("rating", (int) Math.round(s.rating)), Messages.num("games", s.games),
                            Messages.text("override", s.tierOverride == null ? "" : s.tierOverride.name())));
                    }));
                    return Command.SINGLE_SUCCESS;
                })));
        return root.build();
    }

    private int setTier(CommandContext<CommandSourceStack> ctx, @Nullable Tier tier) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        Kit kit = kit(sender, StringArgumentType.getString(ctx, "kit"));
        if (kit == null) return 0;
        plugin.profiles().lookup(name, null).thenAccept(o -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (o.isEmpty()) {
                plugin.messages().send(sender, "profile.not-found", Messages.text("player", name));
                return;
            }
            PlayerProfile p = o.get();
            KitStats stats = plugin.profiles().stats(p, kit.id());
            stats.tierOverride = tier;
            stats.updatedAt = System.currentTimeMillis();
            plugin.tiers().refresh(p);
            plugin.profiles().persistRating(p.uuid(), new ProfileService.RatingWrite(p.id(), kit.id(), stats.snapshot(),
                p.elo(), p.overall()));
            plugin.leaderboards().invalidate(kit.id());
            Player online = Bukkit.getPlayer(p.uuid());
            if (online != null) {
                plugin.tags().update(online);
                plugin.sidebar().refresh(online);
            }
            plugin.messages().send(sender, tier == null ? "tier.cleared" : "tier.set", Messages.text("player", p.name()),
                Messages.comp("kit", kit.displayName()), Messages.comp("tier", plugin.tiers().format(tier)));
        }));
        return Command.SINGLE_SUCCESS;
    }

    // helpers used by AdminCommand
    DuelCorePlugin plugin() {
        return plugin;
    }

    static double doubleArg(CommandContext<CommandSourceStack> ctx, String name) {
        return DoubleArgumentType.getDouble(ctx, name);
    }

    static int intArg(CommandContext<CommandSourceStack> ctx, String name) {
        return IntegerArgumentType.getInteger(ctx, name);
    }

    static Optional<Player> online(String name) {
        return Optional.ofNullable(Bukkit.getPlayerExact(name));
    }
}
