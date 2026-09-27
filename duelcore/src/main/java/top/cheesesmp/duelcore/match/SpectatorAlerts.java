package top.cheesesmp.duelcore.match;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which comings and goings of one match's spectators its fighters are told about (Settings → Spectator alerts), so
 * that /spectate and /leave in a loop can't flood their chat: "now watching" is told at most once per
 * {@link #COOLDOWN_MS} for the same spectator, and "stopped watching" only after a "now watching" that was told. Pure;
 * one per match, main thread only.
 */
final class SpectatorAlerts {

    /** Least time between two "now watching" lines about the same spectator of a match. */
    static final long COOLDOWN_MS = 30_000;

    private final Map<UUID, Long> lastStart = new HashMap<>();
    private final Set<UUID> told = new HashSet<>();

    /** The spectator started watching at {@code now} (ms): true when the fighters are told. */
    boolean start(UUID spectator, long now) {
        Long last = lastStart.get(spectator);
        if (last != null && now - last < COOLDOWN_MS) return false;
        lastStart.put(spectator, now);
        told.add(spectator);
        return true;
    }

    /** The spectator stopped watching: true when the fighters are told (they were told about the start). */
    boolean stop(UUID spectator) {
        return told.remove(spectator);
    }
}
