package top.cheesesmp.duelcore.profile;

/** Per-player toggles stored as a bit field in dc_players.settings. */
public enum Setting {
    DUEL_REQUESTS(0, true),
    SIDEBAR(1, true),
    SOUNDS(2, true),
    CHAT_TAGS(3, true),
    HIDE_HUB_PLAYERS(4, false),
    ALLOW_SPECTATORS(5, true),
    /** Re-join the same queues automatically after a match ("Keep Queuing" in the queue menu). */
    KEEP_QUEUING(6, false),
    /** Chat notices when a friend comes online or someone follows you. */
    FRIEND_ALERTS(7, true),
    /** Accept party invites from anyone (off: friends only). */
    PARTY_INVITES(8, true),
    /** A random music disc plays (to this player only) while searching in a queue. */
    QUEUE_MUSIC(9, true);

    private final int bit;
    private final boolean defaultValue;

    Setting(int bit, boolean defaultValue) {
        this.bit = bit;
        this.defaultValue = defaultValue;
    }

    public int mask() {
        return 1 << bit;
    }

    public boolean defaultValue() {
        return defaultValue;
    }

    public static int defaults() {
        int v = 0;
        for (Setting s : values()) if (s.defaultValue) v |= s.mask();
        return v;
    }
}
