package top.cheesesmp.duelcore.match;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.rating.Tier;

/** One player's side of a match, with live combat stats. */
public final class Participant {

    private final UUID uuid;
    private final String name;
    private final int team;
    private final int profileId;
    private final KitStats before;
    private final @Nullable Tier tierBefore;

    boolean alive = true;
    boolean left;
    int hits;
    int hitsReceived;
    double damageDealt;
    double damageTaken;
    int combo;
    int bestCombo;
    int kills;
    @Nullable UUID lastDamager;
    long lastDamagedAt;
    /** Round tick of the last low-health heartbeat (presentation only). */
    int lastBeat = -1000;

    double ratingAfter = Double.NaN;
    @Nullable Tier tierAfter;

    public Participant(UUID uuid, String name, int team, int profileId, KitStats before, @Nullable Tier tierBefore) {
        this.uuid = uuid;
        this.name = name;
        this.team = team;
        this.profileId = profileId;
        this.before = before;
        this.tierBefore = tierBefore;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public int team() {
        return team;
    }

    public int profileId() {
        return profileId;
    }

    /** Snapshot of the kit stats when the match was created. */
    public KitStats before() {
        return before;
    }

    public @Nullable Tier tierBefore() {
        return tierBefore;
    }

    public @Nullable Tier tierAfter() {
        return tierAfter;
    }

    public boolean alive() {
        return alive;
    }

    public boolean left() {
        return left;
    }

    public int hits() {
        return hits;
    }

    public double damageDealt() {
        return damageDealt;
    }

    public double damageTaken() {
        return damageTaken;
    }

    public int bestCombo() {
        return bestCombo;
    }

    public int kills() {
        return kills;
    }

    public double ratingBefore() {
        return before.rating;
    }

    public double ratingAfter() {
        return ratingAfter;
    }

    public double ratingDelta() {
        return Double.isNaN(ratingAfter) ? 0 : ratingAfter - before.rating;
    }

    void resetRound() {
        alive = true;
        combo = 0;
        lastDamager = null;
        lastDamagedAt = 0;
        lastBeat = -1000;
    }
}
