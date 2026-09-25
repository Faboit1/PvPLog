package top.cheesesmp.duelcore.party;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * A persistent party (dc_parties + dc_party_members): a leader, members in the order they joined, open/private and an
 * optional password hash ({@link PartyPasswords}). Members stay in it while offline. Main thread only.
 */
public final class Party {

    /** One member; {@code id} is the dc_players id. */
    public static final class Member {

        private final int id;
        private final UUID uuid;
        private String name;
        private final long joinedAt;
        private boolean chat;

        public Member(int id, UUID uuid, String name, long joinedAt, boolean chat) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.joinedAt = joinedAt;
            this.chat = chat;
        }

        public int id() {
            return id;
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        void name(String name) {
            this.name = name;
        }

        public long joinedAt() {
            return joinedAt;
        }

        /** Party chat on: everything this member types goes to the party. */
        public boolean chat() {
            return chat;
        }

        void chat(boolean chat) {
            this.chat = chat;
        }
    }

    private final String id;
    private final long createdAt;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private UUID leader;
    private boolean open;
    private @Nullable String passwordHash;

    public Party(String id, UUID leader, boolean open, @Nullable String passwordHash, long createdAt) {
        this.id = id;
        this.leader = leader;
        this.open = open;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public long createdAt() {
        return createdAt;
    }

    public UUID leader() {
        return leader;
    }

    void leader(UUID leader) {
        this.leader = leader;
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    /** The leader's member entry (null only while a broken party is being repaired at load). */
    public @Nullable Member leaderMember() {
        return members.get(leader);
    }

    /** Listed in the party menu for anyone to join (a password, if set, is still asked). */
    public boolean open() {
        return open;
    }

    void open(boolean open) {
        this.open = open;
    }

    public @Nullable String passwordHash() {
        return passwordHash;
    }

    void passwordHash(@Nullable String hash) {
        this.passwordHash = hash;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    /** Members in join order. */
    public Collection<Member> members() {
        return Collections.unmodifiableCollection(members.values());
    }

    public @Nullable Member member(UUID uuid) {
        return members.get(uuid);
    }

    public @Nullable Member member(String name) {
        for (Member m : members.values()) if (m.name().equalsIgnoreCase(name)) return m;
        return null;
    }

    public boolean contains(UUID uuid) {
        return members.containsKey(uuid);
    }

    public int size() {
        return members.size();
    }

    void add(Member member) {
        members.put(member.uuid(), member);
    }

    @Nullable Member remove(UUID uuid) {
        return members.remove(uuid);
    }

    /** Leader first, then the others in join order (for lists). */
    public List<Member> leaderFirst() {
        List<Member> list = new ArrayList<>(members.size());
        Member lead = members.get(leader);
        if (lead != null) list.add(lead);
        for (Member m : members.values()) if (m != lead) list.add(m);
        return list;
    }

    // ------------------------------------------------------------------ pure rules (unit tested)

    /**
     * Who leads after {@code leaving} (null = nobody leaves): an online member first, then the longest-standing one
     * (earliest join, then lowest player id). Null when nobody else is left.
     */
    public static @Nullable Member successor(Collection<Member> members, @Nullable UUID leaving, Predicate<UUID> online) {
        Member best = null;
        for (Member m : members) {
            if (m.uuid().equals(leaving)) continue;
            if (best == null || before(m, best, online)) best = m;
        }
        return best;
    }

    private static boolean before(Member a, Member b, Predicate<UUID> online) {
        boolean aOnline = online.test(a.uuid());
        boolean bOnline = online.test(b.uuid());
        if (aOnline != bOnline) return aOnline;
        if (a.joinedAt() != b.joinedAt()) return a.joinedAt() < b.joinedAt();
        return a.id() < b.id();
    }

    /** Two random teams whose sizes differ by at most one (Party Duel). */
    public static <T> List<List<T>> split(List<T> players, Random random) {
        List<T> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled, random);
        List<T> a = new ArrayList<>();
        List<T> b = new ArrayList<>();
        for (int i = 0; i < shuffled.size(); i++) (i % 2 == 0 ? a : b).add(shuffled.get(i));
        return List.of(a, b);
    }
}
