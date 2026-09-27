package top.cheesesmp.duelcore.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ChatShortcodesTest {

    private static final Map<String, Component> KNOWN = Map.of("SE", Component.text("[SE]"), "Faboit", Component.text("[Faboit]"));

    private static String run(String message, int max) {
        Component out = ChatShortcodes.apply(Component.text(message), max, KNOWN::get);
        return PlainTextComponentSerializer.plainText().serialize(out);
    }

    @Test
    void replacesKnownCodesOnly() {
        assertEquals("hi [SE] and [Faboit] :XX: :nobody: ::", run("hi :SE: and :Faboit: :XX: :nobody: ::", 8));
    }

    @Test
    void capsReplacementsPerMessage() {
        assertEquals("[SE][SE]:SE::SE:", run(":SE::SE::SE::SE:", 2));
        assertEquals(":SE:", run(":SE:", 0));
    }

    @Test
    void ignoresOverlongAndOddTokens() {
        assertEquals(":S: :SE-: :abcdefghijklmnopq:", run(":S: :SE-: :abcdefghijklmnopq:", 8));
    }
}
