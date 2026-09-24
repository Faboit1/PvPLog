package top.cheesesmp.duelcore.hook;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.rating.Tier;

/**
 * %duelcore_tier%, %duelcore_tier_formatted%, %duelcore_points%, %duelcore_wins%, %duelcore_losses%,
 * %duelcore_tier_<kit>%, %duelcore_rating_<kit>%, %duelcore_wins_<kit>%, %duelcore_losses_<kit>%,
 * %duelcore_queued%, %duelcore_live%, %duelcore_in_match%, %duelcore_region%.
 * Only cached data of online players is used; nothing blocks on the database.
 */
final class DuelExpansion extends PlaceholderExpansion {

    private final DuelCorePlugin plugin;

    DuelExpansion(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "duelcore";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        switch (p) {
            case "queued":
                return String.valueOf(plugin.queue().totalQueued());
            case "live":
                return String.valueOf(plugin.matches().count());
            default:
                break;
        }
        if (player == null) return "";
        PlayerProfile profile = plugin.profiles().get(player.getUniqueId());
        if (profile == null) return "";
        switch (p) {
            case "tier":
                return plugin.tiers().label(profile.overall());
            case "tier_formatted":
                return LegacyComponentSerializer.legacySection().serialize(plugin.tiers().format(profile.overall()));
            case "points", "elo":
                return String.valueOf(profile.points());
            case "wins":
                return String.valueOf(profile.totalWins());
            case "losses":
                return String.valueOf(profile.totalLosses());
            case "region":
                return profile.region() == null ? "" : profile.region();
            case "in_match": {
                Match m = plugin.matches().match(player.getUniqueId());
                return String.valueOf(m != null);
            }
            default:
                break;
        }
        int us = p.indexOf('_');
        if (us < 0) return null;
        String field = p.substring(0, us);
        String kit = p.substring(us + 1);
        if (plugin.kits().get(kit) == null) return null;
        KitStats stats = profile.stats(kit);
        return switch (field) {
            case "tier" -> {
                Tier t = plugin.tiers().kitTier(kit, stats);
                yield plugin.tiers().label(t);
            }
            case "rating" -> String.valueOf(stats == null ? (int) plugin.settings().ratingDefault : (int) Math.round(stats.rating));
            case "wins" -> String.valueOf(stats == null ? 0 : stats.wins);
            case "losses" -> String.valueOf(stats == null ? 0 : stats.losses);
            case "games" -> String.valueOf(stats == null ? 0 : stats.games);
            default -> null;
        };
    }
}
