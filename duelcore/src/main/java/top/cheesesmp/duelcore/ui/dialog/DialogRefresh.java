package top.cheesesmp.duelcore.ui.dialog;

/**
 * The rules of the dialog flow ({@link OpenDialogs}) as pure functions: when an open dialog is re-rendered, when the
 * result is sent, when a click keeps its dialog and when a sign from the client means the dialog is gone.
 */
public final class DialogRefresh {

    /** Ticks a click may wait for the dialog it opens (loaded from the database) before its dialog is closed. */
    public static final int AWAIT_TIMEOUT = 100;
    public static final int MIN_INTERVAL = 5;
    public static final int MAX_INTERVAL = 1200;

    private DialogRefresh() {
    }

    /** The configured refresh interval (ticks), kept to a sane range. */
    public static int interval(int configured) {
        return Math.clamp(configured, MIN_INTERVAL, MAX_INTERVAL);
    }

    /**
     * Whether an open dialog is re-rendered now: refreshing is on, the dialog can be refreshed (it has no text input
     * that is being typed), no click is waiting for the next dialog, no animation shows frames of it, and an interval
     * passed since it was shown or last checked.
     */
    public static boolean due(boolean enabled, boolean refreshable, boolean awaiting, boolean animating, int ticksSinceCheck,
                              int interval) {
        return enabled && refreshable && !awaiting && !animating && ticksSinceCheck >= interval;
    }

    /**
     * Whether a re-rendered dialog is sent: only when what it shows changed. (A re-send is a new screen on the client,
     * scrolled back to the top: unchanged dialogs are left alone.)
     */
    public static boolean changed(long shown, long rendered) {
        return shown != rendered;
    }

    /**
     * Whether a click keeps its dialog on screen: it showed the next dialog, or one is being loaded. Otherwise (it
     * started a match, sent a request, failed with a chat message, …) the dialog is closed.
     */
    public static boolean keepsDialog(long serialBefore, long serialAfter, boolean awaiting) {
        return serialAfter != serialBefore || awaiting;
    }

    /** Ticks added to the ping for {@link #signApplies}: the client's tick, the server's tick and their alignment. */
    public static final int SIGN_GRACE = 3;

    /**
     * Whether a sign from the client that no screen is open (a key pressed, an item used, a command typed) means the
     * tracked dialog is gone. Not while the dialog may still be on its way to the client, or the sign on its way back
     * ({@code ping} ms rounded up to ticks, plus {@link #SIGN_GRACE} ticks): the client did that before it got the
     * dialog.
     */
    public static boolean signApplies(int shownAt, int now, int ping) {
        return now - shownAt > SIGN_GRACE + Math.ceilDiv(Math.max(0, ping), 50);
    }

    /** Whether a click has waited too long for its next dialog (the load failed or was refused). */
    public static boolean awaitExpired(int since, int now) {
        return since >= 0 && now - since > AWAIT_TIMEOUT;
    }
}
