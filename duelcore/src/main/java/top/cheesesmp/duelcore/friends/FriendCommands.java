package top.cheesesmp.duelcore.friends;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.command.CommandService;
import top.cheesesmp.duelcore.config.Messages;

/** {@code /friends} (aliases {@code /f}, {@code /friend}) with add|remove|list, plus {@code /follow} and {@code /unfollow}. */
public final class FriendCommands {

    private final DuelCorePlugin plugin;
    private final FriendService service;

    public FriendCommands(DuelCorePlugin plugin, FriendService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register(Commands commands) {
        commands.register(friends(), "Friends: who's online, follow and unfollow players", List.of("f", "friend"));
        commands.register(Commands.literal("follow").requires(CommandService.perm(FriendService.PERMISSION))
            .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                .executes(ctx -> add(ctx)))
            .build(), "Follow a player (mutual follows are friends)", List.of());
        commands.register(Commands.literal("unfollow").requires(CommandService.perm(FriendService.PERMISSION))
            .then(Commands.argument("player", StringArgumentType.word()).suggests(followedNames())
                .executes(ctx -> remove(ctx)))
            .build(), "Stop following a player", List.of());
    }

    private LiteralCommandNode<CommandSourceStack> friends() {
        return Commands.literal("friends").requires(CommandService.perm(FriendService.PERMISSION))
            .executes(ctx -> {
                Player p = player(ctx);
                if (p != null) service.dialogs().open(p, 0, FriendDialogs.Filter.ALL);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.literal("add")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(onlineNames())
                    .executes(this::add)))
            .then(Commands.literal("remove")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(followedNames())
                    .executes(this::remove)))
            .then(Commands.literal("list").executes(ctx -> {
                Player p = player(ctx);
                if (p != null) list(p);
                return Command.SINGLE_SUCCESS;
            }))
            .build();
    }

    private int add(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) return 0;
        service.followByName(p, StringArgumentType.getString(ctx, "player"), null);
        return Command.SINGLE_SUCCESS;
    }

    private int remove(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) return 0;
        service.unfollowByName(p, StringArgumentType.getString(ctx, "player"));
        return Command.SINGLE_SUCCESS;
    }

    /** Chat summary: counts, then every friend (online first) on one line. */
    private void list(Player player) {
        FriendService.Graph g = service.graph(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "friends.loading");
            return;
        }
        List<FriendDialogs.Entry> friends = service.dialogs().entries(g, FriendDialogs.Filter.FRIENDS);
        int online = 0;
        List<Component> names = new ArrayList<>();
        for (FriendDialogs.Entry e : friends) {
            boolean on = e.status() != FriendDialogs.Status.OFFLINE;
            if (on) online++;
            names.add(plugin.messages().get(on ? "friends.list-online" : "friends.list-offline",
                Messages.text("player", e.name()),
                Messages.comp("status", e.status() == FriendDialogs.Status.IN_MATCH
                    ? plugin.messages().get("friends.list-in-match") : Component.empty())));
        }
        plugin.messages().send(player, "friends.list-header", Messages.num("online", online),
            Messages.num("total", friends.size()), Messages.num("following", g.following.size()),
            Messages.num("followers", g.followers.size()));
        if (names.isEmpty()) plugin.messages().send(player, "friends.list-empty");
        else player.sendMessage(Component.join(JoinConfiguration.separator(plugin.messages().parse("<muted>, </muted>")), names));
    }

    private static @Nullable Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) return p;
        if (ctx.getSource().getSender() instanceof Player p) return p;
        ctx.getSource().getSender().sendMessage("This command needs a player.");
        return null;
    }

    private static SuggestionProvider<CommandSourceStack> onlineNames() {
        return (ctx, builder) -> {
            String rem = builder.getRemainingLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(p.getName());
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> followedNames() {
        return (ctx, builder) -> {
            String rem = builder.getRemainingLowerCase();
            if (ctx.getSource().getSender() instanceof Player p) {
                for (String name : service.followedNames(p.getUniqueId())) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }
}
