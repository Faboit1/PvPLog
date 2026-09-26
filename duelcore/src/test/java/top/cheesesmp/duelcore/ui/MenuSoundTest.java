package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MenuSoundTest {

    @Test
    void clicksMapToTheirKind() {
        assertEquals(MenuSound.BACK, MenuSound.forClick("dialog/close"));
        assertEquals(MenuSound.BACK, MenuSound.forClick("queue/close"));
        assertEquals(MenuSound.BACK, MenuSound.forClick("party/close"));
        assertEquals(MenuSound.BACK, MenuSound.forClick("party/menu"));
        assertEquals(MenuSound.BACK, MenuSound.forClick("settings/back"));
        assertEquals(MenuSound.BACK, MenuSound.forClick("duel/deny"));
        assertEquals(MenuSound.BACK, MenuSound.forClick("party/duel-deny"));
        assertEquals(MenuSound.PAGE, MenuSound.forClick("queue/tab"));
        assertEquals(MenuSound.PAGE, MenuSound.forClick("settings/tab"));
        assertEquals(MenuSound.PAGE, MenuSound.forClick("party/page"));
        assertEquals(MenuSound.PAGE, MenuSound.forClick("leaderboard/view"));
        assertEquals(MenuSound.CONFIRM, MenuSound.forClick("duel/accept"));
        assertEquals(MenuSound.CONFIRM, MenuSound.forClick("duel/send"));
        assertEquals(MenuSound.CONFIRM, MenuSound.forClick("party/accept"));
        assertEquals(MenuSound.CONFIRM, MenuSound.forClick("party/disband-confirm"));
        assertEquals(MenuSound.CONFIRM, MenuSound.forClick("settings/country-save"));
        assertEquals(MenuSound.TOGGLE_ON, MenuSound.forClick("friend/follow"));
        assertEquals(MenuSound.TOGGLE_OFF, MenuSound.forClick("friend/unfollow"));
        assertEquals(MenuSound.TOGGLE_OFF, MenuSound.forClick("queue/leave"));
        // toggles play their new state from the handler; the fallback is a plain click
        assertEquals(MenuSound.CLICK, MenuSound.forClick("settings/toggle"));
        assertEquals(MenuSound.CLICK, MenuSound.forClick("queue/toggle"));
        assertEquals(MenuSound.CLICK, MenuSound.forClick("profile/view"));
        assertEquals(MenuSound.CLICK, MenuSound.forClick("something/unknown"));
        assertEquals(MenuSound.CLICK, MenuSound.forClick("noslash"));
    }

    @Test
    void hubItemsAndToggles() {
        assertEquals(MenuSound.BACK, MenuSound.forHubItem("leave-queue"));
        assertEquals(MenuSound.BACK, MenuSound.forHubItem("stop-spectating"));
        assertEquals(MenuSound.CLICK, MenuSound.forHubItem("queue"));
        assertEquals(MenuSound.CLICK, MenuSound.forHubItem("party"));
        assertEquals(MenuSound.TOGGLE_ON, MenuSound.toggle(true));
        assertEquals(MenuSound.TOGGLE_OFF, MenuSound.toggle(false));
    }

    @Test
    void oneSoundPerPlayerPerTick() {
        MenuSounds.Gate gate = new MenuSounds.Gate();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(gate.claim(a, 100));
        assertFalse(gate.claim(a, 100), "the handler's sound wins, the fallback is skipped");
        assertTrue(gate.claim(b, 100), "other players are independent");
        assertTrue(gate.claim(a, 101), "the next tick plays again");
        gate.forget(a);
        assertTrue(gate.claim(a, 101), "forgotten on quit");
    }

    @Test
    void gateStaysSmall() {
        MenuSounds.Gate gate = new MenuSounds.Gate();
        for (int i = 0; i < 600; i++) gate.claim(UUID.randomUUID(), i);
        assertTrue(gate.size() <= 513, "old ticks are dropped: " + gate.size());
    }

    @Test
    void bundledGuiYmlHasEveryKindWithItsDefault() throws Exception {
        YamlConfiguration gui;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("gui.yml")) {
            assertNotNull(in);
            gui = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertTrue(gui.getBoolean("menu-sounds.enabled"));
        for (MenuSound kind : MenuSound.values()) {
            assertEquals(kind.defaultLine(), gui.getString("menu-sounds." + kind.id()), kind.id());
        }
        MenuSoundStyle style = MenuSoundStyle.parse(gui);
        assertTrue(style.enabled());
        assertEquals(List.of(), style.problems());
        for (MenuSound kind : MenuSound.values()) assertFalse(style.sound(kind).isEmpty(), kind.id());
    }

    @Test
    void styleFallsBackToDefaultsAndReportsBadLines() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("menu-sounds.enabled", false);
        y.set("menu-sounds.click", "");
        y.set("menu-sounds.deny", "not a sound!");
        MenuSoundStyle style = MenuSoundStyle.parse(y);
        assertFalse(style.enabled());
        assertTrue(style.sound(MenuSound.CLICK).isEmpty(), "\"\" = none");
        assertEquals("minecraft:item.book.page_turn", style.sound(MenuSound.PAGE).combos().getFirst().getFirst().key());
        assertEquals(1, style.problems().size());
        assertTrue(style.problems().getFirst().startsWith("gui.yml menu-sounds.deny"));
        assertTrue(MenuSoundStyle.parse(new YamlConfiguration()).enabled(), "on without a section");
    }
}
