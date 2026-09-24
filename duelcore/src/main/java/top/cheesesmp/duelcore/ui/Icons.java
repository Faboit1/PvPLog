package top.cheesesmp.duelcore.ui;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;

/**
 * Inline atlas sprites (object components). Sprites are tinted by the text colour, so they are forced white and
 * without shadow to show the real texture. Atlases: {@code items} (item/…), {@code blocks} (block/…),
 * {@code gui} (hud/…, icon/…, mob_effect/…).
 */
public final class Icons {

    private Icons() {
    }

    public static ObjectComponent sprite(String atlas, String path, String fallback) {
        return sprite(atlas, path, fallback, NamedTextColor.WHITE);
    }

    /** A sprite tinted by {@code tint} (multiplied onto the texture; white = the real texture). */
    public static ObjectComponent sprite(String atlas, String path, String fallback, TextColor tint) {
        return Component.object()
            .contents(ObjectContents.sprite(Key.key(atlas), Key.key(path)))
            .fallback(Component.text(fallback))
            .color(tint)
            .shadowColor(ShadowColor.none())
            .decoration(TextDecoration.ITALIC, false)
            .build();
    }

    /**
     * Parses "atlas:path" (e.g. "items:item/mace", "gui:hud/heart/full", "blocks:block/obsidian"), optionally
     * followed by a tint "#rrggbb" ("items:item/dragon_breath#ff6e6e"). A bare path without atlas is treated as an
     * item texture name.
     */
    public static ObjectComponent parse(String spec) {
        String s = spec.trim().toLowerCase(Locale.ROOT);
        TextColor tint = NamedTextColor.WHITE;
        int hash = s.indexOf('#');
        if (hash >= 0) {
            TextColor parsed = TextColor.fromHexString(s.substring(hash));
            if (parsed != null) tint = parsed;
            s = s.substring(0, hash);
        }
        int colon = s.indexOf(':');
        if (colon < 0) return sprite("items", "item/" + s, "•", tint);
        return sprite(s.substring(0, colon), s.substring(colon + 1), "•", tint);
    }

    public static ObjectComponent item(String itemName) {
        String name = itemName.toLowerCase(Locale.ROOT);
        return sprite("items", "item/" + name, "•");
    }

    public static ObjectComponent gui(String path) {
        return sprite("gui", path, "•");
    }

    public static ObjectComponent heart() {
        return gui("hud/heart/full");
    }

    /** Vanilla tab-list ping bars. */
    public static ObjectComponent ping(int ms) {
        String id = ms < 0 ? "icon/ping_unknown"
            : ms < 150 ? "icon/ping_5"
            : ms < 300 ? "icon/ping_4"
            : ms < 600 ? "icon/ping_3"
            : ms < 1000 ? "icon/ping_2"
            : "icon/ping_1";
        return gui(id);
    }

    public static ObjectComponent head(UUID uuid, String name) {
        return Component.object()
            .contents(ObjectContents.playerHead(uuid))
            .fallback(Component.text(name))
            .color(NamedTextColor.WHITE)
            .shadowColor(ShadowColor.none())
            .build();
    }
}
