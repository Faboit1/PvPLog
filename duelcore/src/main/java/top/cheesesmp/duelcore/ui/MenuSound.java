package top.cheesesmp.duelcore.ui;

import java.util.Set;

/**
 * The kinds of menu press sounds ({@link MenuSounds}): each has its own gui.yml line ({@code menu-sounds.<id>}) and a
 * bundled default. {@link #forClick} and {@link #forHubItem} pick the kind a press plays when its handler didn't play
 * a more fitting one (a toggle's new state, a refusal). Pure, no Bukkit.
 */
public enum MenuSound {
    /** A plain button: opens a menu, a player, a kit. */
    CLICK("click", "ui.button.click 1.6 0.25"),
    /** Something switched on: a setting, a queue joined, a favourite, a follow. */
    TOGGLE_ON("toggle-on", "block.note_block.hat 1.5 0.35"),
    /** Something switched off. */
    TOGGLE_OFF("toggle-off", "block.note_block.hat 1.0 0.35"),
    /** Back, Close (and Escape), a declined request, leaving. */
    BACK("back", "ui.button.click 1.1 0.25"),
    /** Accepted, confirmed, saved, sent. */
    CONFIRM("confirm", "block.note_block.chime 1.5 0.4 + block.note_block.chime 2.0 0.4 @2"),
    /** A press that was refused or failed (told in chat or on the menu). */
    DENY("deny", "block.note_block.bass 0.7 0.35"),
    /** Another tab, page or category of the same menu. */
    PAGE("page", "item.book.page_turn 1.2 0.5");

    /** Clicks that switch a tab, page or category of the menu they come from. */
    private static final Set<String> PAGES = Set.of("queue/tab", "settings/tab", "party/page", "leaderboard/view",
        "friend/open");
    /** Clicks that go back, close or turn something down. */
    private static final Set<String> BACKS = Set.of("party/menu", "settings/back", "duel/deny", "party/deny",
        "party/duel-deny", "party/leave", "queue/close", "party/close");
    /** Clicks that accept, confirm, save or send (and happen right away, not after a database round trip). */
    private static final Set<String> CONFIRMS = Set.of("duel/accept", "duel/send", "party/accept", "party/duel-accept",
        "party/disband-confirm", "party/start", "party/invite", "settings/country-save", "queue/join");
    private static final Set<String> ONS = Set.of("friend/follow", "friend/follow-name");
    private static final Set<String> OFFS = Set.of("friend/unfollow", "queue/leave");

    private final String id;
    private final String defaultLine;

    MenuSound(String id, String defaultLine) {
        this.id = id;
        this.defaultLine = defaultLine;
    }

    /** The gui.yml key under {@code menu-sounds}. */
    public String id() {
        return id;
    }

    /** The bundled SoundPool line (same as gui.yml). */
    public String defaultLine() {
        return defaultLine;
    }

    /**
     * The kind a {@code duelcore:<action>} click plays by default. Toggles ({@code queue/toggle},
     * {@code settings/toggle}, …) are {@link #CLICK} here: their handlers play the new state instead.
     */
    public static MenuSound forClick(String action) {
        if (action.equals("dialog/close") || BACKS.contains(action) || action.endsWith("/back")) return BACK;
        if (PAGES.contains(action)) return PAGE;
        if (CONFIRMS.contains(action)) return CONFIRM;
        if (ONS.contains(action)) return TOGGLE_ON;
        if (OFFS.contains(action)) return TOGGLE_OFF;
        return CLICK;
    }

    /** The kind a hub hotbar item plays: leaving a queue or spectating is {@link #BACK}, every menu item a click. */
    public static MenuSound forHubItem(String action) {
        return action.equals("leave-queue") || action.equals("stop-spectating") ? BACK : CLICK;
    }

    /** A toggle's kind by its new state. */
    public static MenuSound toggle(boolean on) {
        return on ? TOGGLE_ON : TOGGLE_OFF;
    }
}
