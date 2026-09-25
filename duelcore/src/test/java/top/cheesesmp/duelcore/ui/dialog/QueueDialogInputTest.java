package top.cheesesmp.duelcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Input;
import org.junit.jupiter.api.Test;

class QueueDialogInputTest {

    /** An input with only key {@code pressed} held (-1 = none): forward, backward, left, right, jump, sneak, sprint. */
    private static Input input(int pressed) {
        return new Input() {
            @Override
            public boolean isForward() {
                return pressed == 0;
            }

            @Override
            public boolean isBackward() {
                return pressed == 1;
            }

            @Override
            public boolean isLeft() {
                return pressed == 2;
            }

            @Override
            public boolean isRight() {
                return pressed == 3;
            }

            @Override
            public boolean isJump() {
                return pressed == 4;
            }

            @Override
            public boolean isSneak() {
                return pressed == 5;
            }

            @Override
            public boolean isSprint() {
                return pressed == 6;
            }
        };
    }

    @Test
    void releasingAllKeysDoesNotCloseTheMenu() {
        // what the client sends when the menu opens while a key was held
        assertFalse(QueueDialog.anyPressed(input(-1)));
    }

    @Test
    void pressingAnyKeyDoes() {
        for (int key = 0; key < 7; key++) assertTrue(QueueDialog.anyPressed(input(key)), "key " + key);
    }
}
