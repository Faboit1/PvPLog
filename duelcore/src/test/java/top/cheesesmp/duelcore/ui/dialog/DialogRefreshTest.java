package top.cheesesmp.duelcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The rules of the dialog flow: refresh timing, re-send only on change, clicks keeping or closing their dialog. */
class DialogRefreshTest {

    @Test
    void refreshIsDueEveryInterval() {
        assertTrue(DialogRefresh.due(true, true, false, false, 20, 20));
        assertTrue(DialogRefresh.due(true, true, false, false, 25, 20));
        assertFalse(DialogRefresh.due(true, true, false, false, 19, 20), "not before the interval");
    }

    @Test
    void noRefreshWhenOffTypingWaitingOrAnimating() {
        assertFalse(DialogRefresh.due(false, true, false, false, 100, 20), "refresh disabled");
        assertFalse(DialogRefresh.due(true, false, false, false, 100, 20), "dialog with a text box / static dialog");
        assertFalse(DialogRefresh.due(true, true, true, false, 100, 20), "a click waits for the next dialog");
        assertFalse(DialogRefresh.due(true, true, false, true, 100, 20), "the queue menu's progress animation runs");
    }

    @Test
    void resentOnlyWhenChanged() {
        assertFalse(DialogRefresh.changed(42L, 42L));
        assertTrue(DialogRefresh.changed(42L, 43L));
    }

    @Test
    void spectateListRefreshesOnlyWithoutASearch() {
        assertTrue(DialogRefresh.spectateRefreshable(""));
        assertTrue(DialogRefresh.spectateRefreshable("  "));
        assertFalse(DialogRefresh.spectateRefreshable("dcbot"));
    }

    @Test
    void aClickKeepsItsDialogOnlyWhenTheNextOneCame() {
        assertTrue(DialogRefresh.keepsDialog(5, 6, false), "showed the next dialog");
        assertTrue(DialogRefresh.keepsDialog(5, 5, true), "the next dialog is loading");
        assertFalse(DialogRefresh.keepsDialog(5, 5, false), "started a match / chat message only: closed");
        assertTrue(DialogRefresh.keepsDialog(5, 7, false), "shown, closed and shown again");
    }

    @Test
    void signsDoNotApplyWhileTheDialogIsOnItsWay() {
        assertFalse(DialogRefresh.signApplies(100, 100, 0), "same tick");
        assertFalse(DialogRefresh.signApplies(100, 101, 0));
        assertTrue(DialogRefresh.signApplies(100, 102, 0));
        // 200 ms ping: four more ticks before a key press can come from a client that has the dialog
        assertFalse(DialogRefresh.signApplies(100, 105, 200));
        assertTrue(DialogRefresh.signApplies(100, 106, 200));
        assertTrue(DialogRefresh.signApplies(100, 102, -1), "unknown ping");
    }

    @Test
    void waitsExpire() {
        assertFalse(DialogRefresh.awaitExpired(-1, 1000), "not waiting");
        assertFalse(DialogRefresh.awaitExpired(100, 100 + DialogRefresh.AWAIT_TIMEOUT));
        assertTrue(DialogRefresh.awaitExpired(100, 101 + DialogRefresh.AWAIT_TIMEOUT));
    }

    @Test
    void intervalIsClamped() {
        assertEquals(20, DialogRefresh.interval(20));
        assertEquals(DialogRefresh.MIN_INTERVAL, DialogRefresh.interval(0));
        assertEquals(DialogRefresh.MIN_INTERVAL, DialogRefresh.interval(-5));
        assertEquals(DialogRefresh.MAX_INTERVAL, DialogRefresh.interval(100_000));
    }

    @Test
    void closeClicksIncludeEscape() {
        // every exit action sends dialog/close, which is also what Escape runs; the older close keys still close
        assertTrue(ClickRouter.isClose(OpenDialogs.CLOSE));
        assertTrue(ClickRouter.isClose("queue/close"));
        assertTrue(ClickRouter.isClose("party/close"));
        assertFalse(ClickRouter.isClose("party/menu"));
        assertFalse(ClickRouter.isClose("queue/tab"));
    }
}
