package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class MotdWidthTest {

    @Test
    void boldGlyphsAreOnePixelWider() {
        assertEquals(MotdService.width("PVP"), MotdService.width(Component.text("PVP"), false));
        assertEquals(MotdService.width("PVP") + 3,
            MotdService.width(Component.text("PVP").decorate(TextDecoration.BOLD), false));
    }

    @Test
    void boldIsInheritedByChildrenUnlessTurnedOff() {
        Component line = Component.text("AB").decorate(TextDecoration.BOLD)
            .append(Component.text("CD"))
            .append(Component.text("EF").decoration(TextDecoration.BOLD, false));
        assertEquals(MotdService.width("ABCDEF") + 4, MotdService.width(line, false));
    }
}
