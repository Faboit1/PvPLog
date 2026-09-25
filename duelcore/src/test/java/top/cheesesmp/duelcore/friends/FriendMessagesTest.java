package top.cheesesmp.duelcore.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Every friends.* message the friends code uses exists in messages.yml (ResourcesTest covers the other sections). */
class FriendMessagesTest {

    private static final Pattern LITERAL = Pattern.compile("\"(friends\\.[a-z0-9_.-]+)\"");

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> map, String prefix, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m) flatten((Map<String, Object>) m, key, out);
            else out.put(key, e.getValue());
        }
    }

    private static Map<String, Object> messages() throws IOException {
        try (InputStream in = FriendMessagesTest.class.getClassLoader().getResourceAsStream("messages.yml")) {
            assertNotNull(in);
            Map<String, Object> out = new HashMap<>();
            flatten(new Yaml().load(in), "", out);
            return out;
        }
    }

    @Test
    void everyFriendsKeyExists() throws IOException {
        Map<String, Object> messages = messages();
        TreeSet<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL.matcher(Files.readString(f));
                while (m.find()) used.add(m.group(1));
            }
        }
        for (FriendDialogs.Status s : FriendDialogs.Status.values()) {
            String id = s.name().toLowerCase(Locale.ROOT).replace('_', '-');
            used.add("friends.dialog.person-" + id);
            used.add("friends.dialog.status-" + id);
        }
        for (FriendDialogs.Relation r : FriendDialogs.Relation.values()) {
            String id = r.name().toLowerCase(Locale.ROOT);
            used.add("friends.dialog.tooltip-" + id);
            used.add("friends.person." + id);
        }
        for (FriendDialogs.Filter f : FriendDialogs.Filter.values()) {
            used.add("friends.dialog.filter-" + f.id());
            used.add("friends.dialog.empty-" + f.id());
        }
        List<String> missing = new ArrayList<>();
        for (String key : used) {
            if (key.endsWith(".") || key.endsWith("-")) continue;
            if (!messages.containsKey(key)) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "missing in messages.yml: " + missing);
        for (String key : List.of("dialog.settings.friend-alerts", "dialog.settings.party-invites")) {
            assertTrue(messages.containsKey(key), key);
        }
    }

    @Test
    void filtersCycle() {
        FriendDialogs.Filter f = FriendDialogs.Filter.ALL;
        List<FriendDialogs.Filter> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seen.add(f);
            f = f.next();
        }
        assertEquals(List.of(FriendDialogs.Filter.ALL, FriendDialogs.Filter.FRIENDS, FriendDialogs.Filter.FOLLOWING,
            FriendDialogs.Filter.FOLLOWERS), seen);
        assertEquals(FriendDialogs.Filter.ALL, f);
        assertEquals(FriendDialogs.Filter.ALL, FriendDialogs.Filter.parse("bogus"));
        assertEquals(FriendDialogs.Filter.FOLLOWERS, FriendDialogs.Filter.parse("followers"));
    }
}
