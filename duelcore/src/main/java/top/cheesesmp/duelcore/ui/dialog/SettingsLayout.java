package top.cheesesmp.duelcore.ui.dialog;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * What the settings menu shows, section by section (pure, unit tested; {@link SettingsDialog} draws it). Every
 * {@link Setting} is in exactly one section, except the two bits of the three-way duel request choice, which are
 * one {@link Kind#DUEL_REQUESTS} row. Entry ids are the keys of the rows' texts (messages.yml
 * {@code dialog.settings.items.<id>}) and icons (gui.yml {@code settings-menu.icons.<id>}).
 */
public final class SettingsLayout {

    /** How a row is drawn and what clicking it does. */
    public enum Kind {
        /** One on/off {@link Setting}; a click flips it. */
        TOGGLE,
        /** Everyone / Friends / Nobody; a click moves to the next. */
        DUEL_REQUESTS,
        /** The configured regions (and "not set") as choices. */
        REGION,
        /** Max opponent ping, with − and + steps ({@link #PING_STEPS}). */
        MAX_PING,
        /** The two-letter country, changed in a small dialog with a text box. */
        COUNTRY
    }

    /** One row: its id, kind and, for {@link Kind#TOGGLE}, the setting. */
    public record Entry(String id, Kind kind, @Nullable Setting setting) {
    }

    /** The menu's tabs, in order. */
    public enum Section {
        /** In a match: who watches, what the action bar tells you, the results screen. */
        GAMEPLAY(toggle(Setting.ALLOW_SPECTATORS), toggle(Setting.TAB_SPECTATORS), toggle(Setting.COMBO_BAR),
            toggle(Setting.HEARTBEAT), toggle(Setting.RESULTS_SCREEN)),
        /** What you see: sidebar, tags, the hub, particles and animations. */
        VISUALS(toggle(Setting.SIDEBAR), toggle(Setting.CHAT_TAGS), toggle(Setting.HIDE_HUB_PLAYERS),
            toggle(Setting.HOTBAR_HINTS), toggle(Setting.MATCH_PARTICLES), toggle(Setting.PROGRESS_REVEAL),
            toggle(Setting.MATCH_FOUND_POP)),
        /** What you hear. */
        SOUNDS(toggle(Setting.SOUNDS), toggle(Setting.MATCH_SOUNDS), toggle(Setting.QUEUE_MUSIC)),
        /** Other players: challenges, invites, alerts, GG. */
        SOCIAL(new Entry("duel-requests", Kind.DUEL_REQUESTS, null), toggle(Setting.PARTY_INVITES),
            toggle(Setting.FRIEND_ALERTS), toggle(Setting.AUTO_GG)),
        /** Searching and matchmaking. */
        QUEUE(toggle(Setting.KEEP_QUEUING), toggle(Setting.SEARCHING_BAR), new Entry("region", Kind.REGION, null),
            new Entry("max-ping", Kind.MAX_PING, null), new Entry("country", Kind.COUNTRY, null));

        private final List<Entry> entries;

        Section(Entry... entries) {
            this.entries = List.of(entries);
        }

        public List<Entry> entries() {
            return entries;
        }

        /** Lower-case id (messages.yml and gui.yml keys, click payloads). */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The section with this id; the first one for anything else (payloads are untrusted). */
        public static Section parse(@Nullable String id) {
            for (Section s : values()) if (s.id().equals(id)) return s;
            return GAMEPLAY;
        }
    }

    /**
     * The max-ping choices from strict to loose: − and + move along them. 0 (any ping) is the loosest, so it is last.
     */
    public static final List<Integer> PING_STEPS = List.of(50, 75, 100, 150, 200, 300, 500, 0);

    private SettingsLayout() {
    }

    /** The row id of a setting, e.g. "hide-hub-players". */
    public static String id(Setting s) {
        return s.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Entry toggle(Setting s) {
        return new Entry(id(s), Kind.TOGGLE, s);
    }

    /** The row with this id in any section, or null (payloads are untrusted). */
    public static @Nullable Entry find(@Nullable String id) {
        for (Section s : Section.values()) {
            for (Entry e : s.entries()) if (e.id().equals(id)) return e;
        }
        return null;
    }

    /**
     * The next max ping from {@code current} one step stricter ({@code dir} &lt; 0) or looser (&gt; 0) along
     * {@link #PING_STEPS}. A value between two steps (set before, or from an old menu) moves to the neighbouring step;
     * the ends stay where they are.
     */
    public static int stepPing(int current, int dir) {
        int strictest = PING_STEPS.getFirst();
        if (current <= 0) return dir < 0 ? PING_STEPS.get(PING_STEPS.size() - 2) : 0;
        if (dir > 0) {
            for (int s : PING_STEPS) if (s == 0 || s > current) return s;
            return 0;
        }
        if (dir < 0) {
            int best = strictest;
            for (int s : PING_STEPS) if (s != 0 && s < current) best = s;
            return best;
        }
        return current;
    }
}
