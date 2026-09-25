package top.cheesesmp.duelcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The refresh re-sends a dialog only when its fingerprint changes: equal content equal, any visible change different. */
class FingerprintTest {

    /** A queue menu row as the menu builds it: kit, "Searching m:ss", a hover and a click. */
    private static Component row(String kit, int waitSeconds, int players) {
        String wait = String.format(Locale.ROOT, "%d:%02d", waitSeconds / 60, waitSeconds % 60);
        return Component.text()
            .append(Component.text(kit, NamedTextColor.GREEN))
            .append(Component.text(" - Searching ", NamedTextColor.GRAY))
            .append(Component.text(wait, NamedTextColor.GREEN))
            .append(Component.text(" · " + players + " players", NamedTextColor.GRAY))
            .hoverEvent(HoverEvent.showText(Component.text(players + " searching")))
            .clickEvent(ClickEvent.custom(Key.key("duelcore", "queue/toggle"), BinaryTagHolder.binaryTagHolder("{kit:\"" + kit + "\"}")))
            .build();
    }

    private static ActionButton button(String label, @Nullable String tooltip, int width, @Nullable String id, @Nullable String payload) {
        DialogAction action = id == null ? null : new DialogAction.CustomClickAction() {
            @Override
            public Key id() {
                return Key.key("duelcore", id);
            }

            @Override
            public @Nullable BinaryTagHolder additions() {
                return payload == null ? null : BinaryTagHolder.binaryTagHolder(payload);
            }
        };
        return new ActionButton() {
            @Override
            public Component label() {
                return Component.text(label);
            }

            @Override
            public @Nullable Component tooltip() {
                return tooltip == null ? null : Component.text(tooltip);
            }

            @Override
            public int width() {
                return width;
            }

            @Override
            public @Nullable DialogAction action() {
                return action;
            }
        };
    }

    @Test
    void sameContentSameFingerprint() {
        long a = Fingerprint.of(Component.text("Queue"), List.of(row("sword", 3, 2), row("axe", 0, 5)));
        long b = Fingerprint.of(Component.text("Queue"), List.of(row("sword", 3, 2), row("axe", 0, 5)));
        assertEquals(a, b);
    }

    @Test
    void aTickingTimerChangesIt() {
        long before = Fingerprint.of(List.of(row("sword", 3, 2)));
        assertNotEquals(before, Fingerprint.of(List.of(row("sword", 4, 2))));
        assertNotEquals(before, Fingerprint.of(List.of(row("sword", 3, 3))), "player count");
        assertNotEquals(before, Fingerprint.of(List.of(row("sword", 63, 2))), "1:03");
    }

    @Test
    void hoverAndClickCount() {
        Component plain = Component.text("● dcbot_b");
        assertNotEquals(Fingerprint.of(plain), Fingerprint.of(plain.hoverEvent(HoverEvent.showText(Component.text("Online")))));
        assertNotEquals(Fingerprint.of(plain), Fingerprint.of(plain.color(NamedTextColor.GREEN)), "online colour");
        assertNotEquals(Fingerprint.of(plain.clickEvent(ClickEvent.custom(Key.key("duelcore", "a"), BinaryTagHolder.binaryTagHolder("{}")))),
            Fingerprint.of(plain.clickEvent(ClickEvent.custom(Key.key("duelcore", "b"), BinaryTagHolder.binaryTagHolder("{}")))));
    }

    @Test
    void orderAndGroupingCount() {
        Component a = Component.text("a");
        Component b = Component.text("b");
        assertNotEquals(Fingerprint.of(a, b), Fingerprint.of(b, a));
        assertNotEquals(Fingerprint.of(List.of(a, b), List.of()), Fingerprint.of(List.of(a), List.of(b)));
        assertNotEquals(Fingerprint.of(a), Fingerprint.of(a, null));
        assertNotEquals(Fingerprint.of((Object) null), Fingerprint.of(List.of()));
    }

    @Test
    void buttonsByLabelTooltipWidthAndClick() {
        ActionButton base = button("dcbot_b", "Friend · online", 110, "friend/person", "{target:\"x\",page:\"0\"}");
        long f = Fingerprint.of(List.of(base));
        assertEquals(f, Fingerprint.of(List.of(button("dcbot_b", "Friend · online", 110, "friend/person", "{target:\"x\",page:\"0\"}"))));
        assertNotEquals(f, Fingerprint.of(List.of(button("dcbot_b", "Friend · offline", 110, "friend/person", "{target:\"x\",page:\"0\"}"))));
        assertNotEquals(f, Fingerprint.of(List.of(button("dcbot_c", "Friend · online", 110, "friend/person", "{target:\"x\",page:\"0\"}"))));
        assertNotEquals(f, Fingerprint.of(List.of(button("dcbot_b", "Friend · online", 120, "friend/person", "{target:\"x\",page:\"0\"}"))));
        assertNotEquals(f, Fingerprint.of(List.of(button("dcbot_b", "Friend · online", 110, "friend/follow", "{target:\"x\",page:\"0\"}"))));
        assertNotEquals(f, Fingerprint.of(List.of(button("dcbot_b", "Friend · online", 110, "friend/person", "{target:\"x\",page:\"1\"}"))));
        // a button without a click (a disabled one) differs from the same button with one
        assertNotEquals(Fingerprint.of(List.of(button("Next", null, 110, null, null))),
            Fingerprint.of(List.of(button("Next", null, 110, "party/page", null))));
    }

    @Test
    void numbersAndFlags() {
        assertEquals(new Fingerprint().add(3).add(true).value(), new Fingerprint().add(3).add(true).value());
        assertNotEquals(new Fingerprint().add(3).add(true).value(), new Fingerprint().add(3).add(false).value());
        assertNotEquals(new Fingerprint().add(3).add(4).value(), new Fingerprint().add(4).add(3).value());
        assertNotEquals(new Fingerprint().value(), new Fingerprint().add(0).value());
    }
}
