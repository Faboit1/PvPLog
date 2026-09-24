package top.cheesesmp.duelcore.api;

import java.util.Collection;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.queue.MatchPolicy;
import top.cheesesmp.duelcore.rating.Tier;

/**
 * Small public API, registered in Bukkit's ServicesManager:
 * {@code Bukkit.getServicesManager().load(DuelCoreApi.class)}. Methods read cached data of online players and must
 * be called on the main thread.
 */
public final class DuelCoreApi {

    private final DuelCorePlugin plugin;

    public DuelCoreApi(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public static @Nullable DuelCoreApi get() {
        return Bukkit.getServicesManager().load(DuelCoreApi.class);
    }

    /** Online player's profile, or null. */
    public @Nullable PlayerProfile profile(UUID player) {
        return plugin.profiles().get(player);
    }

    public @Nullable Tier overallTier(UUID player) {
        PlayerProfile p = profile(player);
        return p == null ? null : p.overall();
    }

    public int points(UUID player) {
        PlayerProfile p = profile(player);
        return p == null ? 0 : p.points();
    }

    public @Nullable Tier kitTier(UUID player, String kit) {
        PlayerProfile p = profile(player);
        KitStats s = p == null ? null : p.stats(kit);
        return plugin.tiers().kitTier(kit, s);
    }

    public @Nullable Match match(UUID player) {
        return plugin.matches().match(player);
    }

    public Collection<Match> liveMatches() {
        return plugin.matches().active();
    }

    /**
     * Replaces the matchmaking latency policy (region/ping preferences). Pass null to restore the configured
     * default. Anti-boosting stays active either way.
     */
    public void setMatchPolicy(@Nullable MatchPolicy policy) {
        plugin.queue().policy(policy);
    }
}
