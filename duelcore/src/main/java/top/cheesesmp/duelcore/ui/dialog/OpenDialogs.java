package top.cheesesmp.duelcore.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.ui.anim.Channel;

/**
 * Which DuelCore dialog each player has open, and its live refresh ({@code plugin.openDialogs()}).
 *
 * <p>Every DuelCore dialog uses after-action NONE: a click keeps the dialog on screen until the server answers with
 * the next dialog, which replaces it without a close-then-reopen flicker. A click that doesn't show another dialog
 * closes it ({@link ClickRouter} does that for every click, see {@link DialogRefresh#keepsDialog}); a click whose next
 * dialog is loaded first calls {@link #awaitNext}. Every close or exit button sends {@code duelcore:dialog/close}, and
 * Escape runs a dialog's exit action (notice: its button, multi-action: the exit action, confirmation: the "no"
 * button) with after-action CLOSE, so closing with Escape is seen as well.
 *
 * <p>The open dialog is forgotten on a close click, Escape, any action that closes it, an inventory opening, quit,
 * world change, joining a match, reload and disable, and on signs that no screen is open any more (a movement key,
 * an item used, a command typed). Refreshable dialogs are re-rendered every {@code dialogs.refresh.interval-
 * ticks} and re-sent only when their {@link Fingerprint} changed, so the scroll position stays put; never while a
 * click waits for its next dialog or an animation shows frames of it (the queue menu's progress fill). Main thread
 * only.
 */
public final class OpenDialogs implements Listener, Runnable {

    /** The click of every close / exit button (and so of Escape). */
    public static final String CLOSE = "dialog/close";

    /** The DuelCore dialogs. */
    public enum Kind {
        QUEUE, PROFILE, LEADERBOARD, SETTINGS, SPECTATE, DUEL_PLAYERS, DUEL_PICKER, RESULTS, NOTICE, DEBUG,
        FRIENDS, FRIEND_PERSON, FRIEND_ADD,
        PARTY_NONE, PARTY_JOIN, PARTY_MENU, PARTY_MEMBER, PARTY_INVITE, PARTY_PRIVACY, PARTY_KITS, PARTY_PVP,
        PARTY_CHALLENGE, PARTY_DISBAND,
        KIT_PICKER
    }

    /** A built dialog and the fingerprint of what it shows. */
    public record Rendered(Dialog dialog, long fingerprint) {
    }

    /**
     * Builds an open dialog again from the current state (it captures what it needs: tab, page, filter, …). Returns
     * null when it doesn't apply any more; the dialog then stays as it is (its clicks re-check everything).
     */
    @FunctionalInterface
    public interface Renderer {
        @Nullable Rendered render(Player player);
    }

    private static final class State {
        @Nullable Kind kind;
        @Nullable Renderer renderer;
        long fingerprint;
        int shownAt;
        int checkedAt;
        /** Bumped on every show and close: a click that didn't change it didn't open a dialog. */
        long serial;
        /** Tick a click started waiting for its next dialog, or -1. */
        int awaitingSince = -1;
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private long shown;
    private long refreshed;
    private long unchanged;

    public OpenDialogs(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static int now() {
        return Bukkit.getCurrentTick();
    }

    // ------------------------------------------------------------------ showing and closing

    /** Shows a dialog that isn't refreshed. */
    public void show(Player player, Kind kind, Dialog dialog) {
        show(player, kind, new Rendered(dialog, 0L), null);
    }

    /** Shows a dialog; with a {@code renderer} it is refreshed while open. */
    public void show(Player player, Kind kind, Rendered rendered, @Nullable Renderer renderer) {
        if (!player.isOnline()) return;
        State s = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        int now = now();
        s.kind = kind;
        s.renderer = renderer;
        s.fingerprint = rendered.fingerprint();
        s.shownAt = now;
        s.checkedAt = now + 1; // first check an interval and a tick later, after the timers shown tick over
        s.serial++;
        s.awaitingSince = -1;
        shown++;
        player.showDialog(rendered.dialog());
    }

    /** Closes the player's dialog (a clear-dialog packet) and forgets it. */
    public void close(Player player) {
        forget(player.getUniqueId());
        player.closeDialog();
    }

    /** The client closed the dialog by itself (no packet is sent). */
    private void forget(UUID uuid) {
        State s = states.get(uuid);
        if (s == null) return;
        boolean wasOpen = s.kind != null;
        s.kind = null;
        s.renderer = null;
        s.serial++;
        s.awaitingSince = -1;
        // an animating queue menu must not show frames of a closed menu
        Player player = wasOpen ? Bukkit.getPlayer(uuid) : null;
        if (player != null && plugin.anim() != null) plugin.anim().cancel(player, Channel.DIALOG);
    }

    /** A sign from the client that no screen is open: forgets a dialog sent before this tick. */
    private void sign(Player player) {
        State s = states.get(player.getUniqueId());
        if (s != null && s.kind != null && DialogRefresh.signApplies(s.shownAt, now(), player.getPing())) {
            forget(player.getUniqueId());
        }
    }

    /**
     * The click being handled opens its dialog later (after loading it): its dialog stays on screen until then (or is
     * closed after {@link DialogRefresh#AWAIT_TIMEOUT} ticks, when the load fails).
     */
    public void awaitNext(Player player) {
        states.computeIfAbsent(player.getUniqueId(), k -> new State()).awaitingSince = now();
    }

    /**
     * The dialog a click waited for won't come (not found, refused: told in chat): the dialog of that click is closed
     * now. Does nothing when no click waits any more (another dialog was shown meanwhile).
     */
    public void abandon(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null || s.awaitingSince < 0) return;
        s.awaitingSince = -1;
        if (s.kind != null) close(player);
    }

    /** Bumped on every show and close of this player's dialog. */
    public long serial(Player player) {
        State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.serial;
    }

    /** True while a click of this player waits for its next dialog. */
    public boolean awaiting(Player player) {
        State s = states.get(player.getUniqueId());
        return s != null && s.awaitingSince >= 0;
    }

    /** The DuelCore dialog the player has open, or null. */
    public @Nullable Kind kind(Player player) {
        State s = states.get(player.getUniqueId());
        return s == null ? null : s.kind;
    }

    /**
     * After a click was handled: a click that neither showed a dialog nor waits for one closes its dialog (the
     * after-action NONE would leave it on screen with nothing happening).
     */
    void afterClick(Player player, long serialBefore) {
        if (!player.isOnline()) return;
        if (!DialogRefresh.keepsDialog(serialBefore, serial(player), awaiting(player))) close(player);
    }

    /** Reload: every tracked dialog is closed (texts, sizes and the refresh settings may have changed). */
    public void reload() {
        closeAll(false);
    }

    /**
     * Plugin disable: closes the dialog of everyone who was shown one (also a dialog forgotten after a sign that
     * turned out wrong), since nothing would answer its clicks any more.
     */
    public void disable() {
        closeAll(true);
        states.clear();
    }

    private void closeAll(boolean everyone) {
        for (Map.Entry<UUID, State> e : List.copyOf(states.entrySet())) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) {
                states.remove(e.getKey());
            } else if (everyone || e.getValue().kind != null || e.getValue().awaitingSince >= 0) {
                close(player);
            }
        }
    }

    // ------------------------------------------------------------------ refresh

    /** Every tick: expired waits, then the dialogs whose refresh is due. */
    @Override
    public void run() {
        if (states.isEmpty()) return;
        int now = now();
        boolean enabled = plugin.settings().dialogRefresh;
        int interval = DialogRefresh.interval(plugin.settings().dialogRefreshTicks);
        for (Map.Entry<UUID, State> e : List.copyOf(states.entrySet())) {
            State s = e.getValue();
            if (s.kind == null && s.awaitingSince < 0) continue;
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) {
                states.remove(e.getKey());
                continue;
            }
            if (DialogRefresh.awaitExpired(s.awaitingSince, now)) {
                s.awaitingSince = -1;
                if (s.kind != null) close(player);
                continue;
            }
            boolean animating = plugin.anim().busy(player, Channel.DIALOG);
            if (!DialogRefresh.due(enabled, s.renderer != null, s.awaitingSince >= 0, animating, now - s.checkedAt, interval)) {
                continue;
            }
            s.checkedAt = now;
            refresh(player, s);
        }
    }

    private void refresh(Player player, State s) {
        Renderer renderer = s.renderer;
        Kind kind = s.kind;
        if (renderer == null || kind == null) return;
        Rendered r;
        try {
            r = renderer.render(player);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Refreshing the " + kind + " dialog of " + player.getName() + " failed: " + ex);
            s.renderer = null;
            return;
        }
        if (r == null || s.kind != kind) return;
        if (!DialogRefresh.changed(s.fingerprint, r.fingerprint())) {
            unchanged++;
            return;
        }
        s.fingerprint = r.fingerprint();
        refreshed++;
        player.showDialog(r.dialog());
    }

    /** For /duelcore debug: open dialogs by kind and the refresh counters. */
    public String stats() {
        Map<Kind, Integer> open = new EnumMap<>(Kind.class);
        int awaiting = 0;
        for (State s : states.values()) {
            if (s.kind != null) open.merge(s.kind, 1, Integer::sum);
            if (s.awaitingSince >= 0) awaiting++;
        }
        return "open=" + open + " awaiting=" + awaiting + " shown=" + shown + " refreshed=" + refreshed + " unchanged=" + unchanged
            + " refresh=" + (plugin.settings().dialogRefresh ? DialogRefresh.interval(plugin.settings().dialogRefreshTicks) + "t" : "off");
    }

    /** For /duelcore debug player: the open dialog. */
    public String describe(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null || s.kind == null) return "no dialog" + (s != null && s.awaitingSince >= 0 ? " (awaiting one)" : "");
        List<String> flags = new ArrayList<>();
        if (s.renderer != null) flags.add("live");
        if (s.awaitingSince >= 0) flags.add("awaiting");
        return "dialog " + s.kind + (flags.isEmpty() ? "" : " " + flags) + " shown " + (now() - s.shownAt) + " ticks ago";
    }

    // ------------------------------------------------------------------ signs that the dialog is gone

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    /** The client closes every screen on a world change (loading screen). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** An inventory (e.g. the kit editor chest) replaces the dialog on the client. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p) forget(p.getUniqueId());
    }

    /** Movement keys, jump, sneak or sprint: they do nothing while a screen is open. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInput(PlayerInputEvent event) {
        if (QueueDialog.anyPressed(event.getInput())) sign(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) sign(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent event) {
        sign(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        sign(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        sign(event.getPlayer());
    }

    /** A typed command: the chat screen replaced the dialog. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        sign(event.getPlayer());
    }

}
