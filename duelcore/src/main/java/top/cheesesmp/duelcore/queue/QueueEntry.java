package top.cheesesmp.duelcore.queue;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One player (or party leader) waiting in a (kit, mode) queue. Immutable except for {@link #ping},
 * which is refreshed each matchmaking pass.
 *
 * @param partyMembers other members queued together (empty for solo)
 */
public final class QueueEntry {

    private final UUID player;
    private final String name;
    private final String kit;
    private final QueueMode mode;
    private final double rating;
    private final long joinedAt;
    private final @Nullable String region;
    private final int maxPing;
    private final java.util.List<UUID> partyMembers;
    private volatile int ping;

    public QueueEntry(UUID player, String name, String kit, QueueMode mode, double rating, long joinedAt,
                      @Nullable String region, int ping, int maxPing, java.util.List<UUID> partyMembers) {
        this.player = player;
        this.name = name;
        this.kit = kit;
        this.mode = mode;
        this.rating = rating;
        this.joinedAt = joinedAt;
        this.region = region;
        this.ping = ping;
        this.maxPing = maxPing;
        this.partyMembers = java.util.List.copyOf(partyMembers);
    }

    public UUID player() {
        return player;
    }

    public String name() {
        return name;
    }

    public String kit() {
        return kit;
    }

    public QueueMode mode() {
        return mode;
    }

    public double rating() {
        return rating;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public @Nullable String region() {
        return region;
    }

    public int ping() {
        return ping;
    }

    public void ping(int ping) {
        this.ping = ping;
    }

    /** 0 = no preference. */
    public int maxPing() {
        return maxPing;
    }

    public java.util.List<UUID> partyMembers() {
        return partyMembers;
    }

    public int size() {
        return 1 + partyMembers.size();
    }

    public double waitSeconds(long now) {
        return Math.max(0, now - joinedAt) / 1000.0;
    }
}
