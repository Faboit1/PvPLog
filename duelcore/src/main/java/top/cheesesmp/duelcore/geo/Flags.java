package top.cheesesmp.duelcore.geo;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.Icons;

/**
 * Country flags next to player names, and the names and regions of countries.yml ({@code plugin.flags()}). A flag is
 * the country's flag head from countries.yml drawn inline, like the queue menu's progress bar
 * ({@link Icons#textureHead}): white, without shadow, as big as a letter. Hovering it shows the country's name and
 * shortcode (messages.yml {@code flags.hover}; typing the shortcode in chat draws the flag, see ChatShortcodes).
 *
 * <p>Who sees a flag: gui.yml {@code flags} switches flags off everywhere or per {@link Place}; a player's own
 * {@link Setting#SHOW_MY_FLAG} (off: their flag is never drawn, for anyone) and the viewer's
 * {@link Setting#SHOW_FLAGS} (off: they see no flags). Places that look the same to everyone (the tab list) only
 * follow the player's own setting. To put a flag before a name, resolve {@code <flag>} with
 * {@link #flag(Place, PlayerProfile, PlayerProfile)} and write {@code <flag><name>} in the template: the component
 * already carries gui.yml {@code flags.format} (the head and a space by default) and is empty when no flag is shown.
 *
 * <p>Thread-safe: heads are cached per countries.yml and gui.yml load, so the async chat renderer may call it.
 */
public final class Flags {

    /** Where flags can be shown (gui.yml {@code flags.show.<id>}). */
    public enum Place {
        /** The tab list, the same for every viewer. */
        TAB,
        /** Above heads (each viewer has their own scoreboard, so it follows the viewer's setting). */
        NAMETAG,
        /** Chat lines. */
        CHAT,
        /** The profile dialog. */
        PROFILE,
        /** The match-found title, the match and spectator sidebars, the results title. */
        MATCH,
        /** Duel requests in chat. */
        DUEL,
        /** Leaderboard lines. */
        LEADERBOARD;

        /** The gui.yml key. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Heads of one countries.yml + gui.yml load, by country code. */
    private record Cache(Countries countries, FlagStyle style, Map<String, Component> heads, Map<String, Component> formatted) {
    }

    private final Supplier<Countries> countries;
    private final Supplier<FlagStyle> style;
    private final Supplier<Messages> messages;
    private volatile @Nullable Cache cache;

    /** The plugin's countries.yml, gui.yml {@code flags} and messages.yml, as they are after every reload. */
    public Flags(DuelCorePlugin plugin, Supplier<Countries> countries) {
        this(countries, () -> plugin.gui().flags, plugin::messages);
    }

    Flags(Supplier<Countries> countries, Supplier<FlagStyle> style, Supplier<Messages> messages) {
        this.countries = countries;
        this.style = style;
        this.messages = messages;
    }

    private Cache cache() {
        Countries c = countries.get();
        FlagStyle style = this.style.get();
        Cache current = cache;
        if (current == null || current.countries() != c || current.style() != style) {
            current = new Cache(c, style, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
            cache = current;
        }
        return current;
    }

    // ------------------------------------------------------------------ countries.yml

    /** countries.yml as loaded now. */
    public Countries countries() {
        return countries.get();
    }

    /** The code is listed in countries.yml. */
    public boolean isKnown(@Nullable String country) {
        return countries.get().isKnown(country);
    }

    /** The country's name ("Germany"); the code itself when it isn't listed, "" for null. */
    public String name(@Nullable String country) {
        if (country == null) return "";
        Countries.Country c = countries.get().get(country);
        return c == null ? country : c.name();
    }

    /** The country's region in countries.yml ("EU"), or null. */
    public @Nullable String region(@Nullable String country) {
        Countries.Country c = countries.get().get(country);
        return c == null ? null : c.region();
    }

    /** The code of what a player typed (a code or a country's name, see {@link Countries#find}), or null. */
    public @Nullable String find(@Nullable String input) {
        return countries.get().find(input);
    }

    // ------------------------------------------------------------------ flags

    /**
     * The bare flag head of a country; empty when flags are off in gui.yml ({@code flags.enabled}), or the country
     * isn't listed or has no flag. Neither place nor privacy is checked: see {@link #flag(Place, PlayerProfile, PlayerProfile)}.
     */
    public Component flag(@Nullable String country) {
        Cache c = cache();
        String cc = Countries.code(country);
        if (!c.style().enabled() || cc == null) return Component.empty();
        return c.heads().computeIfAbsent(cc, code -> {
            Countries.Country listed = c.countries().get(code);
            if (listed == null || listed.flag() == null) return Component.empty();
            Component head = Icons.textureHead(listed.flag(), Component.text(code, NamedTextColor.GRAY), c.style().hat());
            Component hover = messages.get().get("flags.hover", Messages.comp("flag", head),
                Messages.text("country", listed.name()), Messages.text("code", code));
            return head.hoverEvent(HoverEvent.showText(hover));
        });
    }

    /** {@link #flag(String)} in gui.yml {@code flags.format} (by default the head and a space); empty without a flag. */
    public Component formatted(@Nullable String country) {
        Cache c = cache();
        String cc = Countries.code(country);
        if (cc == null) return Component.empty();
        return c.formatted().computeIfAbsent(cc, code -> {
            Component head = flag(code);
            return head.equals(Component.empty()) ? Component.empty()
                : messages.get().parse(c.style().format(), Messages.comp("flag", head));
        });
    }

    /** The country has a flag that is drawn (listed, with a texture, flags on). */
    public boolean hasFlag(@Nullable String country) {
        return !flag(country).equals(Component.empty());
    }

    /** gui.yml shows flags in this place. */
    public boolean shows(Place place) {
        return cache().style().shows(place);
    }

    /**
     * The country {@code subject} shows to others, or null: their country when it is listed and their
     * {@link Setting#SHOW_MY_FLAG} is on.
     */
    public @Nullable String visibleCountry(@Nullable PlayerProfile subject) {
        if (subject == null || subject.country() == null || !subject.setting(Setting.SHOW_MY_FLAG)) return null;
        return isKnown(subject.country()) ? Countries.code(subject.country()) : null;
    }

    /**
     * {@code subject}'s flag as {@code viewer} sees it in {@code place}, formatted (put it right before the name), or
     * empty: gui.yml, the subject's {@link Setting#SHOW_MY_FLAG} and the viewer's {@link Setting#SHOW_FLAGS} (for
     * {@link Place#TAB} pass null as the viewer) all apply.
     */
    public Component flag(Place place, @Nullable PlayerProfile viewer, @Nullable PlayerProfile subject) {
        if (!shows(place) || !wantsFlags(viewer)) return Component.empty();
        return formatted(visibleCountry(subject));
    }

    /**
     * The same for a player known only by their country and stored settings bits (dc_players.settings), e.g. a
     * leaderboard row.
     */
    public Component flag(Place place, @Nullable PlayerProfile viewer, @Nullable String country, int subjectSettings) {
        if (!shows(place) || !wantsFlags(viewer) || !showsOwn(subjectSettings)) return Component.empty();
        return formatted(country);
    }

    /** A viewer's {@link Setting#SHOW_FLAGS} (true for anyone without a profile, e.g. the console). */
    public static boolean wantsFlags(@Nullable PlayerProfile viewer) {
        return viewer == null || viewer.setting(Setting.SHOW_FLAGS);
    }

    /** {@link Setting#SHOW_MY_FLAG} of a stored settings bit field. */
    public static boolean showsOwn(int settingsBits) {
        return Setting.SHOW_MY_FLAG.read(settingsBits);
    }
}
