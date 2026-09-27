package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.junit.jupiter.api.Test;

class HealthTagsTest {

    @Test
    void heartsWithOneDecimal() {
        assertEquals("10", HealthTags.hearts(20));
        assertEquals("7.5", HealthTags.hearts(15));
        assertEquals("0.5", HealthTags.hearts(1));
        assertEquals("3.3", HealthTags.hearts(6.6));
        assertEquals("0", HealthTags.hearts(0));
    }

    @Test
    void colourGoesRedYellowGreen() {
        assertEquals(TextColor.color(0xFF5555), HealthTags.color(0));
        assertEquals(TextColor.color(0xFFCC33), HealthTags.color(0.5));
        assertEquals(TextColor.color(0x55FF55), HealthTags.color(1));
        assertEquals(TextColor.color(0x55FF55), HealthTags.color(1.4)); // clamped (absorption, boosted max)
    }

    @Test
    void hpColourTagColoursTheHearts() {
        Component c = MiniMessage.miniMessage().deserialize("<hp_color>7.5</hp_color>",
            Placeholder.styling("hp_color", HealthTags.color(0.75)));
        assertEquals(HealthTags.color(0.75), c.children().isEmpty() ? c.color() : c.children().getFirst().color());
    }
}
