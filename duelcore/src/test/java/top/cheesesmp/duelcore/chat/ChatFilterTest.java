package top.cheesesmp.duelcore.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.chat.ChatFilter.Verdict;

class ChatFilterTest {

    private static final ChatFilter FILTER = new ChatFilter(YamlConfiguration.loadConfiguration(new InputStreamReader(
        ChatFilterTest.class.getResourceAsStream("/chat-filter.yml"), StandardCharsets.UTF_8)), Logger.getAnonymousLogger());

    private static Verdict v(String s) {
        return FILTER.check(s).verdict();
    }

    @Test
    void blocksSlursAndEvasions() {
        for (String s : new String[] {"nigger", "NIGGA", "n1gg3r", "n.i.g.g.e.r", "n i g g a", "niiiiggaaa", "sandnigger",
            "n​igger", "nіgger" /* Cyrillic і */, "ｎｉｇｇｅｒ", "nígger", "f@ggot", "fag", "fags", "you're a f4g",
            "k y s", "kys", "just kill yourself", "k1ll urself", "heil hitler", "1488", "14/88", "spics", "c00n", "k!ke"}) {
            assertEquals(Verdict.BLOCKED, v(s), s);
        }
    }

    @Test
    void leavesCleanTextAlone() {
        for (String s : new String[] {"gg wp", "that was spicy", "nice snigger", "niggling lag", "raccoon", "cocoon",
            "Scunthorpe", "pass the class", "as it is", "grape", "cumulative", "classic", "assassin", "pakistan",
            "figure it out", "kiss", "ok 1480 elo", "shiitake", "can i go", "an iggy", "Dickens", "cockpit",
            "hancock", "kiss", "kissy", "keys", "i love this server"}) {
            assertEquals(Verdict.CLEAN, v(s), s);
        }
    }

    @Test
    void masksSwearing() {
        ChatFilter.Result r = FILTER.check("what the fuck was that shit");
        assertEquals(Verdict.CENSORED, r.verdict());
        assertEquals("what the **** was that ****", r.text());
        assertEquals("you *******", FILTER.check("you f.u.c.k").text());
        assertEquals(Verdict.CENSORED, v("motherfucker"));
        assertEquals(Verdict.CENSORED, v("sh1t"));
        assertEquals(Verdict.CENSORED, v("fuuuuck"));
    }
}
