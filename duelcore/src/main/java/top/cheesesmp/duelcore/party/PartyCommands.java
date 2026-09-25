package top.cheesesmp.duelcore.party;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.command.CommandService;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.party.PartyService.Mode;
import top.cheesesmp.duelcore.party.PartyService.Outcome;
import top.cheesesmp.duelcore.party.PartyService.Result;

/**
 * {@code /party} (alias {@code /p}) and {@code /pc <message>}. The actions live in {@link PartyService}; failures are
 * reported here with messages.yml party.result.*, successes are announced by the service itself.
 */
public final class PartyCommands {

    private static final String PERMISSION = "duelcore.party";

    private final DuelCorePlugin plugin;

    public PartyCommands(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private PartyService parties() {
        return plugin.parties();
    }

    public LiteralCommandNode<CommandSourceStack> party() {
        return Commands.literal("party").requires(CommandService.perm(PERMISSION))
            .executes(ctx -> run(ctx, p -> parties().dialogs().open(p)))
            .then(Commands.literal("create").executes(ctx -> run(ctx, p ->
                parties().create(p, null).thenAccept(o -> feedback(p, o)))))
            .then(Commands.literal("invite")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(invitable())
                    .executes(ctx -> run(ctx, p -> {
                        String name = StringArgumentType.getString(ctx, "player");
                        Player target = Bukkit.getPlayerExact(name);
                        feedback(p, target == null ? Outcome.of(Result.OFFLINE, name) : parties().invite(p, target));
                    }))))
            .then(Commands.literal("accept")
                .executes(ctx -> run(ctx, p -> feedback(p, parties().accept(p, null, null))))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(inviters())
                    .executes(ctx -> run(ctx, p -> feedback(p, parties().accept(p, null, StringArgumentType.getString(ctx, "player")))))))
            .then(Commands.literal("deny")
                .executes(ctx -> run(ctx, p -> feedback(p, parties().deny(p, null, null))))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(inviters())
                    .executes(ctx -> run(ctx, p -> feedback(p, parties().deny(p, null, StringArgumentType.getString(ctx, "player")))))))
            .then(Commands.literal("join")
                .then(Commands.argument("leader", StringArgumentType.word()).suggests(leaders())
                    .executes(ctx -> join(ctx, null))
                    .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "password"))))))
            .then(Commands.literal("leave").executes(ctx -> run(ctx, p -> feedback(p, parties().leave(p)))))
            .then(Commands.literal("kick")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(members())
                    .executes(ctx -> run(ctx, p -> feedback(p, parties().kick(p, StringArgumentType.getString(ctx, "player")))))))
            .then(Commands.literal("promote")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(members())
                    .executes(ctx -> run(ctx, p -> feedback(p, parties().promote(p, StringArgumentType.getString(ctx, "player")))))))
            .then(Commands.literal("disband").executes(ctx -> run(ctx, p -> feedback(p, parties().disband(p)))))
            .then(Commands.literal("chat").executes(ctx -> run(ctx, p -> feedback(p, parties().toggleChat(p)))))
            .then(Commands.literal("open").executes(ctx -> run(ctx, p -> feedback(p, parties().setOpen(p, true)))))
            .then(Commands.literal("private").executes(ctx -> run(ctx, p -> feedback(p, parties().setOpen(p, false)))))
            .then(Commands.literal("password")
                .executes(ctx -> run(ctx, p -> parties().setPassword(p, null).thenAccept(o -> feedback(p, o))))
                .then(Commands.argument("password", StringArgumentType.greedyString())
                    .executes(ctx -> run(ctx, p -> parties().setPassword(p, StringArgumentType.getString(ctx, "password").strip())
                        .thenAccept(o -> feedback(p, o))))))
            .then(Commands.literal("list").executes(ctx -> run(ctx, this::list)))
            .then(Commands.literal("ffa")
                .executes(ctx -> run(ctx, p -> pickKit(p, Mode.FFA, null)))
                .then(Commands.argument("kit", StringArgumentType.word()).suggests(plugin.commands().kitSuggestions())
                    .executes(ctx -> run(ctx, p -> start(p, Mode.FFA, null, StringArgumentType.getString(ctx, "kit"))))))
            .then(Commands.literal("split")
                .executes(ctx -> run(ctx, p -> pickKit(p, Mode.SPLIT, null)))
                .then(Commands.argument("kit", StringArgumentType.word()).suggests(plugin.commands().kitSuggestions())
                    .executes(ctx -> run(ctx, p -> start(p, Mode.SPLIT, null, StringArgumentType.getString(ctx, "kit"))))))
            .then(Commands.literal("duel")
                .executes(ctx -> run(ctx, p -> pickKit(p, Mode.PVP, null)))
                .then(Commands.literal("accept")
                    .executes(ctx -> run(ctx, p -> feedback(p, parties().acceptChallenge(p, null))))
                    .then(Commands.argument("leader", StringArgumentType.word())
                        .executes(ctx -> run(ctx, p -> feedback(p, parties().acceptChallenge(p,
                            partyId(StringArgumentType.getString(ctx, "leader"))))))))
                .then(Commands.literal("deny")
                    .executes(ctx -> run(ctx, p -> feedback(p, parties().denyChallenge(p, null))))
                    .then(Commands.argument("leader", StringArgumentType.word())
                        .executes(ctx -> run(ctx, p -> feedback(p, parties().denyChallenge(p,
                            partyId(StringArgumentType.getString(ctx, "leader"))))))))
                .then(Commands.argument("leader", StringArgumentType.word()).suggests(leaders())
                    .executes(ctx -> run(ctx, p -> pickKit(p, Mode.PVP, StringArgumentType.getString(ctx, "leader"))))
                    .then(Commands.argument("kit", StringArgumentType.word()).suggests(plugin.commands().kitSuggestions())
                        .executes(ctx -> run(ctx, p -> start(p, Mode.PVP, StringArgumentType.getString(ctx, "leader"),
                            StringArgumentType.getString(ctx, "kit")))))))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> chat() {
        return Commands.literal("pc").requires(CommandService.perm(PERMISSION))
            .then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> run(ctx, p -> feedback(p, parties().chat(p, StringArgumentType.getString(ctx, "message"))))))
            .build();
    }

    // ------------------------------------------------------------------ actions

    private int join(CommandContext<CommandSourceStack> ctx, @Nullable String password) {
        return run(ctx, p -> parties().join(p, StringArgumentType.getString(ctx, "leader"), password == null ? null : password.strip())
            .thenAccept(o -> feedback(p, o)));
    }

    /** Opens the kit picker (or, for Party vs Party without a leader name, the party list). */
    private void pickKit(Player player, Mode mode, @Nullable String leader) {
        Party party = parties().party(player.getUniqueId());
        if (party == null) {
            feedback(player, Outcome.of(parties().loaded() ? Result.NOT_IN_PARTY : Result.NOT_LOADED));
            return;
        }
        Result why = parties().matchBlock(party, player, mode);
        if (why != null) {
            feedback(player, Outcome.of(why));
            return;
        }
        if (mode != Mode.PVP) {
            parties().dialogs().openKits(player, mode, null);
        } else if (leader == null) {
            parties().dialogs().openPvp(player, null);
        } else {
            String id = partyId(leader);
            if (id == null) feedback(player, Outcome.of(Result.NO_PARTY, leader));
            else parties().dialogs().openKits(player, Mode.PVP, id);
        }
    }

    private void start(Player player, Mode mode, @Nullable String leader, String kitId) {
        Kit kit = plugin.kits().get(kitId.toLowerCase(Locale.ROOT));
        if (kit == null || !kit.enabled()) {
            plugin.messages().send(player, "command.unknown-kit", Messages.text("kit", kitId));
            return;
        }
        Outcome o = switch (mode) {
            case FFA -> parties().startFfa(player, kit);
            case SPLIT -> parties().startSplit(player, kit);
            case PVP -> {
                String id = leader == null ? null : partyId(leader);
                yield id == null ? Outcome.of(Result.NO_PARTY, leader == null ? "" : leader) : parties().challenge(player, id, kit);
            }
        };
        feedback(player, o);
    }

    private void list(Player player) {
        Party party = parties().party(player.getUniqueId());
        if (party == null) {
            feedback(player, Outcome.of(parties().loaded() ? Result.NOT_IN_PARTY : Result.NOT_LOADED));
            return;
        }
        Messages msg = plugin.messages();
        msg.send(player, "party.list-header", Messages.num("size", party.size()), Messages.num("max", parties().maxSize()),
            Messages.num("online", parties().onlineCount(party)),
            Messages.comp("privacy", msg.get(party.open() ? "party.dialog.privacy-open" : "party.dialog.privacy-private")),
            Messages.comp("lock", party.hasPassword() ? msg.get("party.dialog.lock") : Component.empty()));
        for (Party.Member m : party.leaderFirst()) {
            msg.send(player, "party.list-line",
                Messages.comp("star", party.isLeader(m.uuid()) ? msg.get("party.dialog.leader-star") : Component.empty()),
                Messages.text("player", m.name()),
                Messages.comp("status", msg.get(switch (parties().status(m.uuid())) {
                    case ONLINE -> "party.status.online";
                    case OFFLINE -> "party.status.offline";
                    case IN_MATCH -> "party.status.in-match";
                })));
        }
    }

    private @Nullable String partyId(String leader) {
        Party party = parties().byLeaderName(leader);
        return party == null ? null : party.id();
    }

    private void feedback(Player player, Outcome outcome) {
        if (outcome.ok() || !player.isOnline()) return;
        plugin.messages().send(player, "party.result." + outcome.result().name().toLowerCase(Locale.ROOT),
            Messages.text("player", outcome.subject()));
    }

    // ------------------------------------------------------------------ helpers

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = ctx.getSource().getExecutor() instanceof Player p ? p
            : ctx.getSource().getSender() instanceof Player p ? p : null;
        if (player == null) {
            ctx.getSource().getSender().sendMessage("This command needs a player.");
            return 0;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }

    private static @Nullable Player sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender() instanceof Player p ? p : null;
    }

    /** Online players who could be invited (not in a party). */
    private SuggestionProvider<CommandSourceStack> invitable() {
        return (ctx, builder) -> {
            Player self = sender(ctx);
            String rem = builder.getRemainingLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(self) || parties().party(p.getUniqueId()) != null) continue;
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(p.getName());
            }
            return builder.buildFuture();
        };
    }

    /** Who invited the sender. */
    private SuggestionProvider<CommandSourceStack> inviters() {
        return (ctx, builder) -> {
            Player self = sender(ctx);
            if (self != null) {
                String rem = builder.getRemainingLowerCase();
                for (PartyService.Invite i : parties().invites(self.getUniqueId())) {
                    if (i.inviterName().toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(i.inviterName());
                }
            }
            return builder.buildFuture();
        };
    }

    /** Members of the sender's party. */
    private SuggestionProvider<CommandSourceStack> members() {
        return (ctx, builder) -> {
            Player self = sender(ctx);
            Party party = self == null ? null : parties().party(self.getUniqueId());
            if (party != null) {
                String rem = builder.getRemainingLowerCase();
                for (Party.Member m : party.members()) {
                    if (!m.uuid().equals(self.getUniqueId()) && m.name().toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(m.name());
                }
            }
            return builder.buildFuture();
        };
    }

    /** Leaders of parties with an online leader. */
    private SuggestionProvider<CommandSourceStack> leaders() {
        return (ctx, builder) -> {
            String rem = builder.getRemainingLowerCase();
            for (Party party : parties().parties()) {
                Party.Member leader = party.leaderMember();
                if (leader == null || !PartyService.online(leader.uuid())) continue;
                if (leader.name().toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(leader.name());
            }
            return builder.buildFuture();
        };
    }
}
