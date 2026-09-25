package top.cheesesmp.duelcore.debug;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.ProgressTracker.Reveal;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierLadder;

/**
 * {@code /tester on|off} ({@code duelcore.tester}): a per-player tester mode for trying the animations without
 * grinding ranked games. While it is on:
 * <ul>
 *   <li>{@code /tester play <preview>} plays an animation on yourself. Features register their own previews with
 *       {@link #preview(String, Consumer)}; the built-in ones are the post-match progress reveals.</li>
 *   <li>Unranked matches, duels and party matches end with a <em>simulated</em> progress reveal (as if the match had
 *       been ranked; ratings don't change), in the hub action bar and for the queue menu
 *       ({@link Reveal#simulated()}).</li>
 * </ul>
 * Memory only: the mode lasts until {@code /tester off} or a restart.
 */
public final class TesterMode {

    private final DuelCorePlugin plugin;
    private final Set<UUID> testers = new HashSet<>();
    private final Map<String, Consumer<Player>> previews = new LinkedHashMap<>();

    public TesterMode(DuelCorePlugin plugin) {
        this.plugin = plugin;
        preview("placement", p -> reveal(p, Sample.PLACEMENT));
        preview("placed", p -> reveal(p, Sample.PLACED));
        preview("elo-up", p -> reveal(p, Sample.ELO_UP));
        preview("elo-down", p -> reveal(p, Sample.ELO_DOWN));
        preview("tier-up", p -> reveal(p, Sample.TIER_UP));
        preview("tier-down", p -> reveal(p, Sample.TIER_DOWN));
    }

    public boolean enabled(UUID player) {
        return testers.contains(player);
    }

    public void set(UUID player, boolean on) {
        if (on) testers.add(player);
        else testers.remove(player);
    }

    /** Registers (or replaces) a preview for {@code /tester play <name>}. Runs on the main thread. */
    public void preview(String name, Consumer<Player> action) {
        previews.put(name, action);
    }

    public Set<String> previews() {
        return previews.keySet();
    }

    // ------------------------------------------------------------------ simulated reveals

    /**
     * After a match that changed no rating: testers get a made-up reveal for the match's kit, as if it had been
     * ranked (win +10..24, loss −8..19, draw ±3; one more game). Called from MatchService.end.
     */
    public void afterMatch(Match m) {
        if (testers.isEmpty()) return;
        Match.EndReason reason = m.endReason();
        if (reason == Match.EndReason.CANCELLED || reason == Match.EndReason.NO_ARENA) return;
        for (Participant p : m.participants()) {
            if (p.left() || !testers.contains(p.uuid())) continue;
            PlayerProfile profile = plugin.profiles().get(p.uuid());
            if (profile == null) continue;
            KitStats before = plugin.profiles().stats(profile, m.kit().id()).snapshot();
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int delta = m.winnerTeam() < 0 ? rnd.nextInt(-3, 4)
                : p.team() == m.winnerTeam() ? rnd.nextInt(10, 25) : -rnd.nextInt(8, 20);
            simulate(p.uuid(), m.kit().id(), before, before.games + 1, before.rating + delta);
        }
    }

    private void simulate(UUID player, String kit, KitStats before, int games, double rating) {
        KitStats after = before.snapshot();
        after.games = games;
        after.rating = rating;
        int placement = plugin.tiers().placementMatches();
        Reveal reveal = Reveal.of(kit, before, after, placement, plugin.tiers().kitTier(kit, before),
            plugin.tiers().kitTier(kit, after), true);
        plugin.progress().record(player, reveal);
    }

    // ------------------------------------------------------------------ previews

    private enum Sample { PLACEMENT, PLACED, ELO_UP, ELO_DOWN, TIER_UP, TIER_DOWN }

    /** Plays a sample reveal in the player's first kit (not recorded: the queue menu is left alone). */
    private void reveal(Player player, Sample sample) {
        List<Kit> kits = plugin.kits().enabled();
        if (kits.isEmpty()) return;
        String kit = kits.getFirst().id();
        int pm = plugin.tiers().placementMatches();
        TierLadder ladder = plugin.tiers().ladder();
        double ht3 = ladder.threshold(kit, Tier.HT3);
        Reveal r = switch (sample) {
            case PLACEMENT -> sample(kit, Math.max(0, pm - 3), Math.max(0, pm - 3) + 1, 1000, 1026);
            case PLACED -> sample(kit, Math.max(0, pm - 1), Math.max(1, pm), 1000, ht3 + 20);
            case ELO_UP -> sample(kit, pm + 4, pm + 5, ht3 + 12, ht3 + 30);
            case ELO_DOWN -> sample(kit, pm + 4, pm + 5, ht3 + 30, ht3 + 18);
            case TIER_UP -> sample(kit, pm + 4, pm + 5, ht3 - 8, ht3 + 12);
            case TIER_DOWN -> sample(kit, pm + 4, pm + 5, ht3 + 6, ht3 - 14);
        };
        plugin.progressReveal().play(player, r);
    }

    private Reveal sample(String kit, int oldGames, int newGames, double oldRating, double newRating) {
        KitStats before = new KitStats(oldRating, 0, 0);
        before.games = oldGames;
        KitStats after = new KitStats(newRating, 0, 0);
        after.games = newGames;
        int pm = plugin.tiers().placementMatches();
        return Reveal.of(kit, before, after, pm, plugin.tiers().kitTier(kit, before), plugin.tiers().kitTier(kit, after), true);
    }

    // ------------------------------------------------------------------ command

    public LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("tester")
            .requires(src -> src.getSender().hasPermission("duelcore.tester"))
            .executes(ctx -> {
                Player p = self(ctx.getSource());
                if (p != null) status(p);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.literal("on").executes(ctx -> toggle(ctx.getSource(), true)))
            .then(Commands.literal("off").executes(ctx -> toggle(ctx.getSource(), false)))
            .then(Commands.literal("play")
                .then(Commands.argument("preview", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (String name : previews.keySet()) if (name.startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> play(ctx.getSource(), StringArgumentType.getString(ctx, "preview")))))
            .build();
    }

    private int toggle(CommandSourceStack src, boolean on) {
        Player p = self(src);
        if (p == null) return 0;
        set(p.getUniqueId(), on);
        plugin.messages().send(p, on ? "tester.turned-on" : "tester.turned-off", Messages.text("previews", String.join(", ", previews.keySet())));
        return Command.SINGLE_SUCCESS;
    }

    private void status(Player p) {
        plugin.messages().send(p, enabled(p.getUniqueId()) ? "tester.status-on" : "tester.status-off",
            Messages.text("previews", String.join(", ", previews.keySet())));
    }

    private int play(CommandSourceStack src, String name) {
        Player p = self(src);
        if (p == null) return 0;
        if (!enabled(p.getUniqueId())) {
            plugin.messages().send(p, "tester.needs-on");
            return 0;
        }
        Consumer<Player> action = previews.get(name);
        if (action == null) {
            plugin.messages().send(p, "tester.unknown-preview", Messages.text("preview", name),
                Messages.text("previews", String.join(", ", previews.keySet())));
            return 0;
        }
        action.accept(p);
        plugin.messages().send(p, "tester.playing", Messages.text("preview", name));
        return Command.SINGLE_SUCCESS;
    }

    private static @Nullable Player self(CommandSourceStack src) {
        if (src.getExecutor() instanceof Player p) return p;
        if (src.getSender() instanceof Player p) return p;
        src.getSender().sendMessage("This command needs a player.");
        return null;
    }

    /** Testers online right now (for /duelcore debug). */
    public int online() {
        int n = 0;
        for (UUID uuid : testers) if (Bukkit.getPlayer(uuid) != null) n++;
        return n;
    }
}
