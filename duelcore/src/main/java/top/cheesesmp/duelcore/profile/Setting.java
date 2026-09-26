package top.cheesesmp.duelcore.profile;

/**
 * Per-player toggles stored as a bit field in dc_players.settings. The settings menu groups them into sections
 * ({@code ui/dialog/SettingsLayout}).
 *
 * <p>Two storage rules. Bits 0-9 hold the value itself: a new row gets the default-on ones from {@link #defaults()},
 * and adding one that defaults to on needed a schema migration to switch it on for existing rows (v5, v6). From
 * {@link #RELATIVE_FROM} on a bit holds "changed from the default" instead, so a 0 bit is always the default and
 * new settings need no migration, whatever their default. Always read and write through {@link #read} and
 * {@link #write} (or {@link PlayerProfile#setting}).
 */
public enum Setting {
    /** Duel requests can be sent to this player (off: nobody). See {@link DuelRequests} with {@link #DUEL_FRIENDS_ONLY}. */
    DUEL_REQUESTS(0, true),
    SIDEBAR(1, true),
    /** Every DuelCore sound effect (the master switch; {@link #MATCH_SOUNDS} is a part of it). */
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
    QUEUE_MUSIC(9, true),

    // ---- from here on: relative bits (set = changed from the default), no migration needed

    /** With {@link #DUEL_REQUESTS} on: duel requests only from friends. */
    DUEL_FRIENDS_ONLY(10, false),
    /** The hub hotbar's action bar hints ("Right click to play"). */
    HOTBAR_HINTS(11, true),
    /** The post-match progress: Elo / placement count on the action bar, tier-up titles, the hub XP bar fill and tier ring. */
    PROGRESS_REVEAL(12, true),
    /** Particles of matches this player is in or watches: fight-start rings, round spirals, deaths, fireworks, confetti. */
    MATCH_PARTICLES(13, true),
    /** The hit-combo and kill counter on the action bar during a match. */
    COMBO_BAR(14, true),
    /** The low-health heartbeat (sound and action bar pulse). */
    HEARTBEAT(15, true),
    /** Match-found, countdown, "FIGHT!", kill and victory / defeat sounds (only while {@link #SOUNDS} is on). */
    MATCH_SOUNDS(16, true),
    /** The results screen that opens back in the hub after a match (the chat summary is always sent). */
    RESULTS_SCREEN(17, true),
    /** Says "gg" to the match (fighters and spectators) when a match of yours ends. */
    AUTO_GG(18, false),
    /** Spectators of your match are listed in your tab list while you fight. */
    TAB_SPECTATORS(19, true),
    /** The queue's "searching" action bar while in a queue. */
    SEARCHING_BAR(20, true),
    /** The totem-pop animation with the kit's icon when a match is found. */
    MATCH_FOUND_POP(21, true);

    /** The first bit that is stored relative to the default. */
    public static final int RELATIVE_FROM = 10;

    private final int bit;
    private final boolean defaultValue;

    Setting(int bit, boolean defaultValue) {
        this.bit = bit;
        this.defaultValue = defaultValue;
    }

    public int bit() {
        return bit;
    }

    public int mask() {
        return 1 << bit;
    }

    public boolean defaultValue() {
        return defaultValue;
    }

    /** True when the stored bit means "changed from the default" rather than the value. */
    public boolean relative() {
        return bit >= RELATIVE_FROM;
    }

    /** This setting's value in a stored bit field. */
    public boolean read(int bits) {
        boolean set = (bits & mask()) != 0;
        return relative() ? set != defaultValue : set;
    }

    /** {@code bits} with this setting set to {@code value}. */
    public int write(int bits, boolean value) {
        boolean set = relative() ? value != defaultValue : value;
        return set ? bits | mask() : bits & ~mask();
    }

    /** The stored bit field of a new player: every setting at its default. */
    public static int defaults() {
        int v = 0;
        for (Setting s : values()) v = s.write(v, s.defaultValue);
        return v;
    }
}
