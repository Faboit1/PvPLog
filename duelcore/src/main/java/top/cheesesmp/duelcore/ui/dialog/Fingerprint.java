package top.cheesesmp.duelcore.ui.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import org.jspecify.annotations.Nullable;

/**
 * A cheap 64-bit fingerprint of what a dialog shows (title, body texts, buttons and whatever else decides its
 * content), so the live refresh ({@link OpenDialogs}) re-sends a dialog only when something visible changed.
 * Components hash by their content (text, style, hover and click events, children), buttons by label, tooltip,
 * width and click, lists element by element (with their length, so {@code [a, b]} differs from {@code [ab]}).
 * Pure: no server needed.
 */
public final class Fingerprint {

    private static final long SEED = 0x9E3779B97F4A7C15L;
    private static final long NULL = 0x6E756C6C_00000001L;
    private static final long LIST = 0x6C697374_00000002L;
    private static final long BUTTON = 0x62746E00_00000003L;

    private long hash = SEED;
    private int parts;

    /** The fingerprint of {@code parts} in order. */
    public static long of(@Nullable Object... parts) {
        Fingerprint f = new Fingerprint();
        for (Object part : parts) f.add(part);
        return f.value();
    }

    /** Adds one part: a component, button, list (element by element), string, number or anything with a content hash. */
    public Fingerprint add(@Nullable Object part) {
        switch (part) {
            case null -> mix(NULL);
            case ActionButton button -> button(button);
            case Iterable<?> list -> {
                int n = 0;
                for (Object o : list) {
                    add(o);
                    n++;
                }
                mix(LIST ^ n);
            }
            default -> mix(part.hashCode());
        }
        return this;
    }

    public Fingerprint add(long value) {
        mix(value);
        return this;
    }

    public Fingerprint add(boolean value) {
        mix(value ? 0x7472756EL : 0x66616C73L);
        return this;
    }

    private void button(ActionButton b) {
        mix(BUTTON);
        add(b.label());
        add(b.tooltip());
        mix(b.width());
        DialogAction action = b.action();
        if (action instanceof DialogAction.CustomClickAction click) {
            add(click.id().asString());
            add(click.additions() == null ? null : click.additions().string());
        } else {
            add(action == null ? null : action.getClass().getName());
        }
    }

    private void mix(long v) {
        hash = Long.rotateLeft(hash ^ (v * 0xC2B2AE3D27D4EB4FL), 31) * 0x9E3779B97F4A7C15L + 0x165667B19E3779F9L;
        parts++;
    }

    /** The fingerprint so far. */
    public long value() {
        long z = hash ^ parts;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
