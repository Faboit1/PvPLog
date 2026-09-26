package top.cheesesmp.duelcore.profile;

/**
 * Who may send a player duel requests: a three-way setting kept in two bits, {@link Setting#DUEL_REQUESTS} (on:
 * someone may) and {@link Setting#DUEL_FRIENDS_ONLY} (only friends). Nobody = requests off, whatever the second bit;
 * so rows from before the second bit keep what they had (on = everyone, off = nobody).
 */
public enum DuelRequests {
    EVERYONE, FRIENDS, NOBODY;

    public static DuelRequests of(boolean requests, boolean friendsOnly) {
        return !requests ? NOBODY : friendsOnly ? FRIENDS : EVERYONE;
    }

    /** Whether a request from someone ({@code friends}: the two are friends) gets through. */
    public boolean allows(boolean friends) {
        return this == EVERYONE || this == FRIENDS && friends;
    }

    /** The next choice in the settings menu (Everyone → Friends → Nobody → Everyone). */
    public DuelRequests next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Lower-case id, used in messages.yml keys. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
