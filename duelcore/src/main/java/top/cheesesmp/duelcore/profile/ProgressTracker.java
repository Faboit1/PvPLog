package top.cheesesmp.duelcore.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.rating.Tier;

/**
 * What the last ranked matches changed, per player and kit, kept until it has been shown ({@code plugin.progress()}).
 * Every rated match records a {@link Reveal} (before/after games, rating and tier). Two independent consumers read
 * it:
 * <ul>
 *   <li>the post-match action bar in the hub takes the newest one with {@link #takeLatest};</li>
 *   <li>the queue menu animates a kit's bar from old to new with {@link #pendingReveal} and then {@link #consume}s
 *       it. Several matches before the menu is opened merge into one reveal (oldest "before", newest "after").</li>
 * </ul>
 * Memory only, cleared on quit, bounded ({@value #MAX_KITS} kits per player, {@value #MAX_PLAYERS} players). Main
 * thread only.
 */
public final class ProgressTracker implements Listener {

    static final int MAX_KITS = 32;
    static final int MAX_PLAYERS = 1000;

    /**
     * One kit's change. Elo values are rounded ratings; a tier is null while unranked. {@code simulated} marks
     * reveals made up for testers (/tester) whose ratings did not really change.
     */
    public record Reveal(String kit, int oldGames, int newGames, int placementMatches, int oldElo, int newElo,
                         @Nullable Tier oldTier, @Nullable Tier newTier, boolean wasPlaced, boolean placed,
                         boolean simulated, long at) {

        /** Before/after from the stats snapshot taken when the match started and the stats after rating. */
        public static Reveal of(String kit, KitStats before, KitStats after, int placementMatches, @Nullable Tier oldTier,
                                @Nullable Tier newTier, boolean simulated) {
            return new Reveal(kit, before.games, after.games, placementMatches, (int) Math.round(before.rating),
                (int) Math.round(after.rating), oldTier, newTier, before.placed(placementMatches), after.placed(placementMatches),
                simulated, System.currentTimeMillis());
        }

        /** Still in placement after this match (the reveal shows placement progress, not Elo). */
        public boolean inPlacement() {
            return !placed;
        }

        /** Placement finished with this match: the first tier in this kit. */
        public boolean placedNow() {
            return placed && !wasPlaced;
        }

        public int eloDelta() {
            return newElo - oldElo;
        }

        /** Placement progress 0..1 before and after. */
        public double oldProgress() {
            return placementMatches <= 0 ? 1 : Math.min(1, (double) oldGames / placementMatches);
        }

        public double newProgress() {
            return placementMatches <= 0 ? 1 : Math.min(1, (double) newGames / placementMatches);
        }

        /** Better tier than before (both ranked). A first tier is {@link #placedNow()}, not a tier up. */
        public boolean tierUp() {
            return oldTier != null && newTier != null && newTier.isBetterThan(oldTier);
        }

        public boolean tierDown() {
            return oldTier != null && newTier != null && oldTier.isBetterThan(newTier);
        }

        /** The older "before" with this "after" (two matches in a row before the menu showed the first). */
        Reveal since(Reveal older) {
            return new Reveal(kit, older.oldGames, newGames, placementMatches, older.oldElo, newElo, older.oldTier,
                newTier, older.wasPlaced, placed, older.simulated && simulated, at);
        }
    }

    /** player → kit → reveal waiting for the queue menu (insertion order = oldest first). */
    private final Map<UUID, LinkedHashMap<String, Reveal>> menu = new LinkedHashMap<>();
    /** player → newest reveal not yet shown in the hub. */
    private final LinkedHashMap<UUID, Reveal> hub = new LinkedHashMap<>();

    /** Records a rated match's change for both consumers. */
    public void record(UUID player, Reveal reveal) {
        hub.remove(player);
        hub.put(player, reveal);
        while (hub.size() > MAX_PLAYERS) hub.remove(hub.keySet().iterator().next());
        LinkedHashMap<String, Reveal> kits = menu.computeIfAbsent(player, k -> new LinkedHashMap<>());
        Reveal older = kits.remove(reveal.kit());
        kits.put(reveal.kit(), older == null ? reveal : reveal.since(older));
        while (kits.size() > MAX_KITS) kits.remove(kits.keySet().iterator().next());
        // re-insert the player so the least recently active one is evicted first
        menu.put(player, menu.remove(player));
        while (menu.size() > MAX_PLAYERS) {
            menu.remove(menu.keySet().iterator().next());
        }
    }

    /** The reveal the queue menu should animate for this kit, or null. Doesn't consume it. */
    public @Nullable Reveal pendingReveal(UUID player, String kit) {
        LinkedHashMap<String, Reveal> kits = menu.get(player);
        return kits == null ? null : kits.get(kit);
    }

    /** Kits with a reveal waiting for the queue menu. */
    public List<String> pendingKits(UUID player) {
        LinkedHashMap<String, Reveal> kits = menu.get(player);
        return kits == null ? List.of() : new ArrayList<>(kits.keySet());
    }

    /** The queue menu has shown this kit's reveal. */
    public void consume(UUID player, String kit) {
        LinkedHashMap<String, Reveal> kits = menu.get(player);
        if (kits == null) return;
        kits.remove(kit);
        if (kits.isEmpty()) menu.remove(player);
    }

    /** The newest reveal for the post-match action bar, removed (the queue menu keeps its own copy). */
    public @Nullable Reveal takeLatest(UUID player) {
        return hub.remove(player);
    }

    public void forget(UUID player) {
        menu.remove(player);
        hub.remove(player);
    }

    public void clear() {
        menu.clear();
        hub.clear();
    }

    public int size() {
        return menu.size();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }
}
