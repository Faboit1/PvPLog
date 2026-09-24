package top.cheesesmp.duelcore.hook;

import top.cheesesmp.duelcore.DuelCorePlugin;

/** Loaded only when PlaceholderAPI is enabled. No PlaceholderAPI types leak outside this package. */
public final class PapiHook {

    private static DuelExpansion expansion;

    private PapiHook() {
    }

    public static boolean register(DuelCorePlugin plugin) {
        expansion = new DuelExpansion(plugin);
        return expansion.register();
    }

    public static void unregister() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
    }
}
