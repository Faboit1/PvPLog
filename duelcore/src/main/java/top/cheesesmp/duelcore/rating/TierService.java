package top.cheesesmp.duelcore.rating;

import java.util.EnumMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;

/**
 * Turns stats into tiers, the overall standing and their display. Immutable; replaced on reload.
 * The overall standing is either the player's overall Elo (average rating of the kits they finished placement in)
 * or the legacy sum of kit tier points, depending on tiers.yml {@code overall-mode}.
 */
public final class TierService {

    public enum OverallMode { ELO, POINTS }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TierLadder ladder;
    private final String unrankedLabel;
    private final Map<Tier, String> formats;
    private final String unrankedFormat;
    private final OverallMode mode;

    public TierService(TierLadder ladder, String unrankedLabel, Map<Tier, String> formats, String unrankedFormat,
                       OverallMode mode) {
        this.mode = mode;
        this.ladder = ladder;
        this.unrankedLabel = unrankedLabel;
        this.formats = new EnumMap<>(formats);
        this.unrankedFormat = unrankedFormat;
    }

    public TierLadder ladder() {
        return ladder;
    }

    public OverallMode mode() {
        return mode;
    }

    public int placementMatches() {
        return ladder.placementMatches();
    }

    /** Kit tier, or null while unranked (placement not finished). A staff override always wins. */
    public @Nullable Tier kitTier(String kit, @Nullable KitStats stats) {
        if (stats == null) return null;
        if (stats.tierOverride != null) return stats.tierOverride;
        if (stats.games < ladder.placementMatches()) return null;
        return ladder.kitTier(kit, stats.rating);
    }

    public int points(PlayerProfile profile) {
        int total = 0;
        for (Map.Entry<String, KitStats> e : profile.allStats().entrySet()) {
            total += ladder.points(kitTier(e.getKey(), e.getValue()));
        }
        return total;
    }

    /** Overall Elo: average rating over the kits whose placement is finished (0 when none). */
    public int overallElo(PlayerProfile profile) {
        double sum = 0;
        int n = 0;
        for (Map.Entry<String, KitStats> e : profile.allStats().entrySet()) {
            KitStats s = e.getValue();
            if (s.games < ladder.placementMatches()) continue;
            sum += s.rating;
            n++;
        }
        return n == 0 ? 0 : (int) Math.round(sum / n);
    }

    /** The overall standing value: overall Elo or global points (see {@link OverallMode}). */
    public int standing(PlayerProfile profile) {
        return mode == OverallMode.ELO ? overallElo(profile) : points(profile);
    }

    /** Overall tier, or null when the player has no ranked kit yet. */
    public @Nullable Tier overall(PlayerProfile profile, int value) {
        boolean anyRanked = false;
        for (Map.Entry<String, KitStats> e : profile.allStats().entrySet()) {
            if (kitTier(e.getKey(), e.getValue()) != null) {
                anyRanked = true;
                break;
            }
        }
        if (!anyRanked) return null;
        return mode == OverallMode.ELO ? ladder.kitTier("overall", value) : ladder.overallTier(value);
    }

    /** Recomputes and stores the standing value + overall tier on the profile. */
    public void refresh(PlayerProfile profile) {
        int value = standing(profile);
        profile.standing(value, overall(profile, value));
    }

    /** Placeholders for an overall standing value: {@code <elo>}, {@code <points>} (both the raw number) and
     * {@code <standing>} (the number with its unit, from messages.yml standing.elo / standing.points). */
    public net.kyori.adventure.text.minimessage.tag.resolver.TagResolver standingTags(
            top.cheesesmp.duelcore.config.Messages messages, int value) {
        return net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            top.cheesesmp.duelcore.config.Messages.num("elo", value),
            top.cheesesmp.duelcore.config.Messages.num("points", value),
            top.cheesesmp.duelcore.config.Messages.comp("standing", messages.get(
                mode == OverallMode.ELO ? "standing.elo" : "standing.points",
                top.cheesesmp.duelcore.config.Messages.num("value", value))));
    }

    public String label(@Nullable Tier tier) {
        return tier == null ? unrankedLabel : tier.name();
    }

    public Component format(@Nullable Tier tier) {
        String template = tier == null ? unrankedFormat : formats.getOrDefault(tier, "<tier>");
        return MM.deserialize(template, Placeholder.unparsed("tier", label(tier)));
    }
}
