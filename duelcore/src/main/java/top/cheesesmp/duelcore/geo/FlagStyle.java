package top.cheesesmp.duelcore.geo;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * gui.yml {@code flags}: whether country flags are drawn at all, how ({@code format}, where {@code <flag>} is the
 * head; the hat layer or not) and in which {@link Flags.Place}s.
 */
public record FlagStyle(boolean enabled, String format, boolean hat, Set<Flags.Place> places) {

    /** Everything on, "&lt;flag&gt; " (the head and a space before the name). */
    public static final FlagStyle DEFAULT = new FlagStyle(true, "<flag> ", true, EnumSet.allOf(Flags.Place.class));

    public FlagStyle {
        places = places.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(places));
    }

    public static FlagStyle parse(ConfigurationSection y) {
        EnumSet<Flags.Place> places = EnumSet.noneOf(Flags.Place.class);
        for (Flags.Place p : Flags.Place.values()) if (y.getBoolean("flags.show." + p.id(), true)) places.add(p);
        return new FlagStyle(y.getBoolean("flags.enabled", true), y.getString("flags.format", DEFAULT.format()),
            y.getBoolean("flags.hat", true), places);
    }

    /** Flags are drawn in {@code place}. */
    public boolean shows(Flags.Place place) {
        return enabled && places.contains(place);
    }
}
