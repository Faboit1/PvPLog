package top.cheesesmp.duelcore.kit.editor;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.api.event.MatchStartEvent;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.hub.HubService;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.ui.MatchSounds;
import top.cheesesmp.duelcore.ui.anim.Channel;

/**
 * The per-player kit editor: a 6-row chest laid out like the real inventory (rows 1–3 = inventory slots 9–35, row 4 =
 * the hotbar, row 5 = the armour (locked), the offhand slot and an info item, row 6 = Save, Reset, Clear and Cancel).
 *
 * <p>Every kit item carries its source position ({@link KitLayout}) in its data, so identical items never merge and
 * the layout read back is exact. Every click in the editor is cancelled and, when it is a plain left or right click on
 * one of the 37 editable slots, done here instead: pick up the whole stack, put it down, or swap it with the one on
 * the cursor. Anything else (shift, number keys, the offhand key, drops, double clicks, drags, the player's own
 * inventory, locked slots) does nothing. On close or save the item on the cursor goes back to an empty editable slot,
 * and only a complete permutation of the kit's items is ever saved.
 *
 * <p>While editing, the player's own inventory (the hub hotbar) is put aside and comes back when the editor closes.
 * Closing without saving keeps the old layout; a match found meanwhile (queue, duel, party) closes the editor and
 * saves the arrangement.
 */
public final class KitEditor implements Listener {

    public static final String PERMISSION = "duelcore.kiteditor";

    private static final int SIZE = 54;
    /** Row 5: helmet, chestplate, leggings, boots (locked), the offhand label, the info item. */
    private static final int[] ARMOR = {36, 37, 38, 39};
    private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};
    private static final int OFFHAND_LABEL = 41;
    private static final int INFO = 44;
    /** Row 6. */
    private static final int SAVE = 45;
    private static final int RESET = 48;
    private static final int CLEAR = 50;
    private static final int CANCEL = 53;
    /** Editable positions in the order a returned cursor item looks for an empty one: hotbar, inventory, offhand. */
    private static final int[] RETURN_ORDER = returnOrder();

    /** Identifies the editor's inventory. */
    static final class Holder implements InventoryHolder {
        private @Nullable Inventory inventory;

        @Override
        public Inventory getInventory() {
            if (inventory == null) throw new IllegalStateException("kit editor inventory not created yet");
            return inventory;
        }
    }

    /** One player's open editor. */
    private static final class Session {
        final UUID uuid;
        final Kit kit;
        final Holder holder;
        final Inventory inv;
        final boolean[] filled;
        final int kitHash;
        /** The player's inventory before the editor opened (41 slots), given back on close. */
        final @Nullable ItemStack[] stash;
        /** The layout in use (saved or default): changes are measured against it. */
        int[] saved;
        /** Editor slot the item on the cursor was picked up from (it goes back there on close when still free). */
        int pickedFrom = -1;
        /** The unsaved-changes state the Save button shows (its glint). */
        boolean shownDirty;
        boolean refreshQueued;
        /** Save/Cancel pressed: the editor closes next tick and ignores clicks until then. */
        boolean closing;
        /** Saved or refused already: closing says nothing more. */
        boolean quiet;
        /** A match was found: save the arrangement on close. */
        boolean autoSave;

        Session(UUID uuid, Kit kit, Holder holder, Inventory inv, boolean[] filled, int kitHash, @Nullable ItemStack[] stash,
                int[] saved) {
            this.uuid = uuid;
            this.kit = kit;
            this.holder = holder;
            this.inv = inv;
            this.filled = filled;
            this.kitHash = kitHash;
            this.stash = stash;
            this.saved = saved;
        }
    }

    private final DuelCorePlugin plugin;
    private final KitLayouts layouts;
    private final KitPicker picker;
    /** Item data key: the source position (0–36) of a kit item; -1 on the editor's fixed items. */
    private final NamespacedKey key;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private @Nullable BukkitTask watchdog;

    public KitEditor(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.layouts = new KitLayouts(plugin);
        this.picker = new KitPicker(plugin, this);
        this.key = new NamespacedKey(plugin, "kit_editor");
    }

    /** Listeners, the "kiteditor" clicks, the hub item, the layout source of KitManager.apply and previews. */
    public void enable() {
        for (String problem : plugin.gui().kitEditor.problems()) plugin.getLogger().warning(problem);
        layouts.enable();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.clicks().register("kiteditor", this::click);
        plugin.hub().registerItem("kit-editor", PERMISSION, player -> {
            if (player.hasPermission(PERMISSION)) picker.open(player);
            else plugin.messages().send(player, "command.no-permission");
        });
        KitManager.layouts((player, kit) -> {
            int[] layout = layouts.layout(player, kit);
            if (layout == null && layouts.pending(player.getUniqueId())) {
                // (asked once per match: KitManager.apply keeps the choice for the later rounds)
                plugin.messages().send(player, "kit-editor.layout-not-loaded", Messages.comp("kit", kit.displayName()),
                    Messages.comp("kit_icon", kit.sprite()));
            }
            return layout;
        });
        watchdog = Bukkit.getScheduler().runTaskTimer(plugin, this::watch, 20L, 20L);
        // /animtest play kit-editor-save | kit-editor-pick: the editor's feedback sounds
        plugin.tester().preview("kit-editor-save", p -> sound(p, "save"));
        plugin.tester().preview("kit-editor-pick", p -> {
            sound(p, "pick");
            p.getScheduler().runDelayed(plugin, t -> sound(p, "place"), null, 6L);
        });
    }

    /** Plugin disable: closes every editor (unsaved changes are dropped) and gives the players their items back. */
    public void disable() {
        KitManager.layouts(null);
        if (watchdog != null) watchdog.cancel();
        for (Session s : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(s.uuid);
            if (player == null) {
                sessions.remove(s.uuid);
                continue;
            }
            s.quiet = true;
            finish(player, s, false); // listeners don't run while disabling: finish first, then close
            player.closeInventory();
        }
        sessions.clear();
        layouts.disable();
    }

    /**
     * After /duelcore reload: layouts made for a kit whose items changed are reset now (the players are told), not
     * at their next match. Returns the gui.yml kit-editor problems.
     */
    public List<String> reload() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            // a running match keeps its own copy of the kit: its players keep their layout until their next match
            if (plugin.matches().match(p.getUniqueId()) == null) layouts.dropStale(p);
        }
        return plugin.gui().kitEditor.problems();
    }

    public KitLayouts layouts() {
        return layouts;
    }

    public KitPicker picker() {
        return picker;
    }

    /** True while the player has the editor open. */
    public boolean editing(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    // ------------------------------------------------------------------ opening

    /** Why the player can't edit a kit right now (a messages.yml key), or null. */
    @Nullable String refusal(Player player) {
        UUID uuid = player.getUniqueId();
        if (plugin.matches().match(uuid) != null) return "kit-editor.in-match";
        if (plugin.spectate().spectating(uuid) != null) return "kit-editor.spectating";
        if (!plugin.hub().isHubWorld(player.getWorld())) return "kit-editor.not-in-hub";
        if (!plugin.profiles().ready() || plugin.profiles().get(player) == null) return "kit-editor.not-ready";
        return null;
    }

    /** Opens the editor for a kit (loads the player's layouts first when needed). */
    public void open(Player player, Kit kit) {
        if (!player.hasPermission(PERMISSION)) {
            plugin.messages().send(player, "command.no-permission");
            return;
        }
        if (!kit.enabled()) {
            plugin.messages().send(player, "command.unknown-kit", Messages.text("kit", kit.id()));
            return;
        }
        String refusal = refusal(player);
        if (refusal != null) {
            plugin.messages().send(player, refusal);
            plugin.openDialogs().abandon(player); // (after loading: a match was found meanwhile)
            return;
        }
        if (!layouts.loaded(player.getUniqueId())) {
            // a dialog it was opened from stays until the editor opens; closed meanwhile (Escape): it doesn't open
            long ticket = plugin.openDialogs().awaitNext(player);
            layouts.load(player).thenRun(() -> {
                if (player.isOnline() && layouts.loaded(player.getUniqueId())) {
                    plugin.openDialogs().continueAwait(player, ticket, () -> open(player, kit));
                } else if (player.isOnline()) {
                    plugin.messages().send(player, "kit-editor.not-ready");
                    plugin.openDialogs().abandon(player, ticket);
                }
            });
            return;
        }
        Session old = sessions.get(player.getUniqueId());
        if (old != null) close(player, old);

        plugin.openDialogs().close(player); // the chest replaces the dialog it was opened from
        plugin.anim().cancel(player, Channel.DIALOG); // an animating queue menu must not re-open over the editor
        String[] parts = KitManager.fingerprint(kit);
        boolean[] filled = KitLayout.filled(parts);
        int[] start = layouts.layout(player, kit);
        if (start == null) start = KitLayout.identity(filled);

        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(kit));
        holder.inventory = inv;
        decorate(inv, kit, player);
        place(inv, kit, start);

        PlayerInventory own = player.getInventory();
        ItemStack[] stash = own.getContents();
        for (int i = 0; i < stash.length; i++) stash[i] = stash[i] == null ? null : stash[i].clone();
        Session s = new Session(player.getUniqueId(), kit, holder, inv, filled, KitLayout.hash(parts), stash, start);
        sessions.put(s.uuid, s);
        own.clear(); // the hub hotbar is put aside while editing (it comes back on close)
        InventoryView view = player.openInventory(inv);
        if (view == null || view.getTopInventory() != inv) {
            // another plugin refused the inventory: give everything back
            s.quiet = true;
            finish(player, s, false);
            return;
        }
        sound(player, "open");
    }

    /** Opens the kit picker dialog (the hub item, /kit, /kiteditor). */
    public void openPicker(Player player) {
        picker.open(player);
    }

    // ------------------------------------------------------------------ building the chest

    private Messages msg() {
        return plugin.messages();
    }

    /**
     * The chest title. It never changes while the editor is open: a new title re-sends the window (a new open-screen
     * packet), and the client's new screen swallows the next mouse release, i.e. the put-down click of an item on the
     * cursor. The unsaved state is shown by the Save button's glint instead (a plain slot update).
     */
    private Component title(Kit kit) {
        return msg().get("kit-editor.title", Messages.comp("kit", kit.displayName()));
    }

    /** The fixed slots: armour (locked), offhand label, info, the buttons and fillers. */
    private void decorate(Inventory inv, Kit kit, Player player) {
        KitEditorStyle style = plugin.gui().kitEditor;
        ItemStack filler = fixed(style.filler(), Component.empty(), List.of());
        filler.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        for (int slot = 36; slot < SIZE; slot++) inv.setItem(slot, filler.clone());
        inv.setItem(KitLayout.EDITOR_OFFHAND, null);
        ItemStack[] armor = {kit.helmet(), kit.chestplate(), kit.leggings(), kit.boots()};
        for (int i = 0; i < ARMOR.length; i++) inv.setItem(ARMOR[i], armorItem(armor[i], PIECES[i], style));
        inv.setItem(OFFHAND_LABEL, fixed(style.offhandLabel(), msg().get("kit-editor.item.offhand-name"),
            lines("kit-editor.item.offhand-lore")));
        inv.setItem(INFO, fixed(style.info(), msg().get("kit-editor.item.info-name", Messages.comp("kit", kit.displayName())),
            lines("kit-editor.item.info-lore")));
        inv.setItem(SAVE, fixed(style.save(), msg().get("kit-editor.item.save-name"), lines("kit-editor.item.save-lore")));
        inv.setItem(RESET, fixed(style.reset(), msg().get("kit-editor.item.reset-name"), lines("kit-editor.item.reset-lore")));
        inv.setItem(CLEAR, clearButton(player, kit));
        inv.setItem(CANCEL, fixed(style.cancel(), msg().get("kit-editor.item.cancel-name"), lines("kit-editor.item.cancel-lore")));
    }

    private ItemStack clearButton(Player player, Kit kit) {
        boolean custom = layouts.custom(player.getUniqueId(), kit);
        return fixed(plugin.gui().kitEditor.clear(), msg().get("kit-editor.item.clear-name"),
            lines(custom ? "kit-editor.item.clear-lore" : "kit-editor.item.clear-lore-none"));
    }

    private ItemStack armorItem(@Nullable ItemStack piece, String name, KitEditorStyle style) {
        if (piece == null || piece.getType().isAir()) {
            return fixed(style.emptyArmor(), msg().get("kit-editor.item.no-armor",
                Messages.comp("piece", msg().get("kit-editor.item.pieces." + name))), lines("kit-editor.item.locked"));
        }
        ItemStack item = piece.clone();
        List<Component> lore = new ArrayList<>();
        ItemLore existing = item.getData(DataComponentTypes.LORE);
        if (existing != null) lore.addAll(existing.lines());
        lore.addAll(lines("kit-editor.item.locked"));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        tag(item, -1);
        return item;
    }

    private ItemStack fixed(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.CUSTOM_NAME, name);
        if (!lore.isEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(
            DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS).build());
        tag(item, -1);
        return item;
    }

    /** A messages.yml text as lore lines (list entries, or lines split at &lt;newline&gt;). */
    private List<Component> lines(String key, TagResolver... resolvers) {
        String raw = msg().raw(key);
        List<Component> out = new ArrayList<>();
        if (raw.isBlank()) return out;
        for (String line : raw.split("<newline>|<br>", -1)) out.add(msg().parse(line, resolvers));
        return out;
    }

    /** Puts the kit's items (tagged copies) into the editable slots as {@code map} says; clears the others. */
    private void place(Inventory inv, Kit kit, int[] map) {
        ItemStack[] sources = KitManager.sources(kit);
        for (int pos = 0; pos < KitLayout.SIZE; pos++) {
            int source = map[pos];
            ItemStack item = source == KitLayout.EMPTY || sources[source] == null ? null : sources[source].clone();
            if (item != null) tag(item, source);
            inv.setItem(KitLayout.editorSlot(pos), item);
        }
    }

    private void tag(ItemStack item, int source) {
        item.editPersistentDataContainer(pdc -> pdc.set(key, PersistentDataType.INTEGER, source));
    }

    /** The source position of an editor kit item, -1 for the editor's fixed items, -2 for anything else. */
    private int source(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) return -2;
        Integer v = item.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v == null ? -2 : v;
    }

    private boolean kitItem(@Nullable ItemStack item) {
        int source = source(item);
        return source >= 0 && source < KitLayout.SIZE;
    }

    /** The arrangement in the chest right now (the cursor not included). */
    private int[] read(Session s) {
        int[] map = new int[KitLayout.SIZE];
        for (int pos = 0; pos < KitLayout.SIZE; pos++) {
            ItemStack item = s.inv.getItem(KitLayout.editorSlot(pos));
            map[pos] = kitItem(item) ? source(item) : KitLayout.EMPTY;
        }
        return map;
    }

    private boolean dirty(Player player, Session s) {
        return kitItem(player.getItemOnCursor()) || !Arrays.equals(read(s), s.saved);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true); // everything is done by hand below; vanilla never moves an item in the editor
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.holder != holder || s.closing) return;
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= SIZE) return; // outside the window or the player's own inventory
        ClickType click = event.getClick();
        boolean plain = click == ClickType.LEFT || click == ClickType.RIGHT;
        switch (raw) {
            case SAVE -> {
                if (plain) save(player, s);
                return;
            }
            case RESET -> {
                if (plain) reset(player, s);
                return;
            }
            case CLEAR -> {
                if (plain) clear(player, s);
                return;
            }
            case CANCEL -> {
                if (plain) cancel(player, s);
                return;
            }
            default -> {
                // an editable slot, or a locked one
            }
        }
        if (KitLayout.position(raw) < 0 || !plain) {
            if (click != ClickType.DOUBLE_CLICK) sound(player, "deny");
            return;
        }
        move(player, s, raw);
    }

    /** Whole-stack pick up, put down or swap with the cursor on one editable slot. */
    private void move(Player player, Session s, int slot) {
        ItemStack cursor = player.getItemOnCursor();
        ItemStack there = s.inv.getItem(slot);
        boolean holding = !cursor.isEmpty();
        boolean occupied = there != null && !there.isEmpty();
        if (holding && !kitItem(cursor)) return; // (never happens: nothing else can get onto the cursor here)
        if (occupied && !kitItem(there)) return;
        if (!holding && !occupied) return;
        if (!holding) {
            s.inv.setItem(slot, null);
            player.setItemOnCursor(there);
            s.pickedFrom = slot;
            sound(player, "pick");
        } else if (!occupied) {
            s.inv.setItem(slot, cursor);
            player.setItemOnCursor(null);
            s.pickedFrom = -1;
            sound(player, "place");
        } else {
            s.inv.setItem(slot, cursor);
            player.setItemOnCursor(there);
            s.pickedFrom = slot;
            sound(player, "swap");
        }
        changed(player, s);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        // a drag over several slots would split the stack; a single-slot drag arrives as a click instead
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }

    /** After a move: the Save button's glint follows the unsaved state (next tick, once per tick; never the title). */
    private void changed(Player player, Session s) {
        if (s.refreshQueued) return;
        s.refreshQueued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            s.refreshQueued = false;
            if (sessions.get(s.uuid) != s || !player.isOnline()) return;
            boolean dirty = dirty(player, s);
            if (dirty == s.shownDirty) return;
            s.shownDirty = dirty;
            ItemStack save = s.inv.getItem(SAVE);
            if (save != null) {
                save.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, dirty);
                s.inv.setItem(SAVE, save);
            }
        });
    }

    /** Puts the item on the cursor back into the editor: its old slot when still free, else the first free one. */
    private void returnCursor(Player player, Session s) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor.isEmpty() || !kitItem(cursor)) return;
        int slot = s.pickedFrom >= 0 && empty(s.inv.getItem(s.pickedFrom)) ? s.pickedFrom : -1;
        for (int i = 0; slot < 0 && i < RETURN_ORDER.length; i++) {
            int candidate = KitLayout.editorSlot(RETURN_ORDER[i]);
            if (empty(s.inv.getItem(candidate))) slot = candidate;
        }
        if (slot < 0) return; // impossible: the item came from one of the 37 slots, so one is free
        s.inv.setItem(slot, cursor);
        player.setItemOnCursor(null);
        s.pickedFrom = -1;
    }

    private static boolean empty(@Nullable ItemStack item) {
        return item == null || item.isEmpty();
    }

    private static int[] returnOrder() {
        int[] order = new int[KitLayout.SIZE];
        for (int i = 0; i < KitLayout.SIZE; i++) order[i] = i; // hotbar 0–8, inventory 9–35, offhand 36
        return order;
    }

    // ------------------------------------------------------------------ buttons

    private void save(Player player, Session s) {
        returnCursor(player, s);
        int[] map = read(s);
        if (!KitLayout.valid(map, s.filled)) {
            sound(player, "deny");
            msg().send(player, "kit-editor.invalid");
            return;
        }
        if (!sameKit(s)) {
            msg().send(player, "kit-editor.kit-changed", Messages.comp("kit", s.kit.displayName()));
            s.quiet = true;
            closeLater(player, s);
            return;
        }
        if (!layouts.save(player, s.kit, map)) {
            sound(player, "deny");
            msg().send(player, "kit-editor.save-failed");
            return;
        }
        s.saved = map;
        s.quiet = true;
        sound(player, "save");
        msg().send(player, KitLayout.isIdentity(map, s.filled) ? "kit-editor.saved-default" : "kit-editor.saved",
            Messages.comp("kit", s.kit.displayName()), Messages.comp("kit_icon", s.kit.sprite()));
        closeLater(player, s);
    }

    private void reset(Player player, Session s) {
        returnCursor(player, s);
        place(s.inv, s.kit, KitLayout.identity(s.filled));
        sound(player, "reset");
        msg().actionBar(player, "kit-editor.reset-done");
        changed(player, s);
    }

    private void clear(Player player, Session s) {
        returnCursor(player, s);
        boolean had = layouts.clear(player, s.kit);
        s.saved = KitLayout.identity(s.filled);
        place(s.inv, s.kit, s.saved);
        s.inv.setItem(CLEAR, clearButton(player, s.kit));
        sound(player, had ? "clear" : "deny");
        msg().send(player, had ? "kit-editor.cleared" : "kit-editor.already-default", Messages.comp("kit", s.kit.displayName()));
        changed(player, s);
    }

    private void cancel(Player player, Session s) {
        sound(player, "cancel");
        closeLater(player, s);
    }

    /** Closes next tick (an inventory can't be closed from inside its click event); clicks are ignored until then. */
    private void closeLater(Player player, Session s) {
        s.closing = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessions.get(s.uuid) == s) close(player, s);
        });
    }

    /** Closes the player's editor now (the close event finishes the session; finished here if it didn't). */
    private void close(Player player, Session s) {
        if (player.isOnline() && player.getOpenInventory().getTopInventory() == s.inv) player.closeInventory();
        if (sessions.get(s.uuid) == s) finish(player, s, false);
    }

    /** True when the kit wasn't changed (reloaded with other items) since the editor opened. */
    private boolean sameKit(Session s) {
        Kit current = plugin.kits().get(s.kit.id());
        return current != null && current.enabled() && KitLayout.hash(KitManager.fingerprint(current)) == s.kitHash;
    }

    // ------------------------------------------------------------------ closing

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.holder != holder) return;
        finish(player, s, event.getReason() == InventoryCloseEvent.Reason.DISCONNECT);
    }

    /**
     * Ends a session: the cursor item goes back into the editor, the arrangement is saved when a match took the
     * player away, the editor is emptied and the player's own items come back (unless a match or spectating owns the
     * inventory now). Runs inside the close event, before vanilla would hand the cursor item to the player.
     */
    private void finish(Player player, Session s, boolean quitting) {
        if (!sessions.remove(s.uuid, s)) return;
        returnCursor(player, s);
        int[] map = read(s);
        boolean inMatch = plugin.matches().match(s.uuid) != null;
        boolean changed = !Arrays.equals(map, s.saved);
        if ((s.autoSave || inMatch) && !s.quiet) {
            if (changed) {
                boolean ok = KitLayout.valid(map, s.filled) && sameKit(s) && layouts.save(player, s.kit, map);
                msg().send(player, ok ? "kit-editor.auto-saved" : "kit-editor.auto-save-failed",
                    Messages.comp("kit", s.kit.displayName()), Messages.comp("kit_icon", s.kit.sprite()));
            }
        } else if (changed && !s.quiet && !quitting) {
            msg().send(player, "kit-editor.discarded", Messages.comp("kit", s.kit.displayName()));
        }
        s.inv.clear();
        sweep(player);
        if (quitting) {
            player.getInventory().setContents(s.stash); // saved with the player as it was; the hub resets it on join
        } else if (!inMatch && plugin.spectate().spectating(s.uuid) == null) {
            giveBack(player, s);
        }
    }

    /**
     * The player's own items again. A plain hub hotbar (nothing but hub items) is rebuilt, since the queue item may
     * have changed meanwhile; anything else (a builder's blocks, worn armour, items gathered for a kit save) comes
     * back exactly as it was, with only a stale Play / Leave queue item swapped for the current one.
     */
    private void giveBack(Player player, Session s) {
        HubService hub = plugin.hub();
        boolean hubItems = false;
        boolean others = false;
        for (ItemStack item : s.stash) {
            if (empty(item)) continue;
            if (hub.action(item) != null) hubItems = true;
            else others = true;
        }
        boolean inHub = hub.isHubWorld(player.getWorld());
        if (hubItems && !others && inHub) {
            hub.giveItems(player);
            return;
        }
        PlayerInventory own = player.getInventory();
        own.setContents(s.stash);
        if (!hubItems || !inHub) return;
        String queueKey = plugin.queue().isQueued(s.uuid) ? "leave-queue" : "queue";
        GuiConfig.HotbarItem def = plugin.gui().item(queueKey);
        for (int i = 0; i < s.stash.length; i++) {
            String action = hub.action(s.stash[i]);
            if (("queue".equals(action) || "leave-queue".equals(action)) && !queueKey.equals(action)) {
                own.setItem(i, def == null ? null : hub.build(player, queueKey, def));
            }
        }
    }

    /** Removes any editor item from the player's own inventory and cursor (they must never leave the editor). */
    private void sweep(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (source(contents[i]) != -2) inv.setItem(i, null);
        }
        if (source(player.getItemOnCursor()) != -2) player.setItemOnCursor(null);
    }

    /** A match was made (queue, duel, party): close the editors of its players, saving their arrangement. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMatchStart(MatchStartEvent event) {
        for (Participant p : event.getMatch().participants()) {
            Session s = sessions.get(p.uuid());
            Player player = Bukkit.getPlayer(p.uuid());
            if (s == null || player == null) continue;
            s.autoSave = true;
            close(player, s);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) finish(event.getPlayer(), s, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sweep(event.getPlayer()); // (a crash while editing could have saved editor items with the player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        // belt and braces: an editor item that somehow reached the real inventory is destroyed, neither dropped nor
        // handed back (a cancelled drop would put it back into the inventory)
        if (source(event.getItemDrop().getItemStack()) == -2) return;
        event.setCancelled(false);
        event.getItemDrop().remove();
    }

    /** Every second: a session whose editor isn't open any more (no close event seen) is finished. */
    private void watch() {
        for (Session s : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(s.uuid);
            if (player == null) {
                sessions.remove(s.uuid, s);
            } else if (player.getOpenInventory().getTopInventory() != s.inv) {
                finish(player, s, false);
            }
        }
    }

    // ------------------------------------------------------------------ clicks from dialogs

    /**
     * {@code duelcore:kiteditor/open {kit}} (the kit picker's kit buttons; any dialog may send it) and
     * {@code kiteditor/menu} (the picker).
     */
    private void click(Player player, String action, Map<String, String> data, @Nullable DialogResponseView view) {
        switch (action) {
            case "kiteditor/open" -> {
                Kit kit = plugin.kits().get(data.getOrDefault("kit", ""));
                if (kit == null || !kit.enabled()) picker.open(player);
                else open(player, kit);
            }
            case "kiteditor/menu" -> picker.open(player);
            default -> {
                // unknown kiteditor action: ignore
            }
        }
    }

    // ------------------------------------------------------------------ sounds

    void sound(Player player, String name) {
        MatchSounds.play(plugin, player, plugin.gui().kitEditor.sound(name).pick(ThreadLocalRandom.current()), 0);
    }
}
