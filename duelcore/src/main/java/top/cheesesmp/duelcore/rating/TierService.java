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
 * Turns stats into tiers, the overall Elo and their display. Immutable; replaced on reload.
 * Overall Elo = average rating of the kits a player finished placement in; the overall tier uses the same thresholds
 * as kit tiers (tiers.yml kit-thresholds.overall when present, otherwise default).
 */
public final class TierService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TierLadder ladder;
    private final String unrankedLabel;
    private final Map<Tier, String> formats;
    private final String unrankedFormat;

    public TierService(TierLadder ladder, String unrankedLabel, Map<Tier, String> formats, String unrankedFormat) {
        this.ladder = ladder;
        this.unrankedLabel = unrankedLabel;
        this.formats = new EnumMap<>(Tier.class); // not new EnumMap<>(map): that throws for an empty non-EnumMap
        this.formats.putAll(formats);
        this.unrankedFormat = unrankedFormat;
    }

    public TierLadder ladder() {
        return ladder;
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

    /** Overall Elo: average rating over the kits whose placement is finished (0 when none). */
    public int overallElo(PlayerProfile profile) {
        double sum = 0;
        int n = 0;
        for (KitStats s : profile.allStats().values()) {
            if (s.games < ladder.placementMatches()) continue;
            sum += s.rating;
            n++;
        }
        return n == 0 ? 0 : (int) Math.round(sum / n);
    }

    /** Overall tier from the overall Elo, or null when the player has no ranked kit yet. */
    public @Nullable Tier overall(PlayerProfile profile, int elo) {
        for (Map.Entry<String, KitStats> e : profile.allStats().entrySet()) {
            if (kitTier(e.getKey(), e.getValue()) != null) return ladder.kitTier("overall", elo);
        }
        return null;
    }

    /** Recomputes and stores the overall Elo + overall tier on the profile. */
    public void refresh(PlayerProfile profile) {
        int elo = overallElo(profile);
        profile.standing(elo, overall(profile, elo));
    }

    /** The overall Elo for display: the number, or "—" while no kit has finished placement. */
    public static String eloText(@Nullable PlayerProfile profile) {
        return profile == null || profile.overall() == null ? "—" : String.valueOf(profile.elo());
    }

    public String label(@Nullable Tier tier) {
        return tier == null ? unrankedLabel : tier.name();
    }

    public Component format(@Nullable Tier tier) {
        String template = tier == null ? unrankedFormat : formats.getOrDefault(tier, "<tier>");
        return MM.deserialize(template, Placeholder.unparsed("tier", label(tier)));
    }
}
