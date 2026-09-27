package top.cheesesmp.duelcore.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/** Who sees whose flag: gui.yml switches, the viewer's Flags and the player's Show my flag; the head itself. */
class FlagsTest {

    private static final String DE_FLAG = "5e7899b4806858697e283f084d9173fe487886453774626b24bd8cfecc77b3f";
    private static final Countries COUNTRIES = Countries.parse(new StringReader(
        "countries:\n  DE: { name: Germany, region: EU, flag: \"" + DE_FLAG + "\" }\n  BV: { name: Bouvet Island }\n"));

    private final AtomicReference<FlagStyle> style = new AtomicReference<>(FlagStyle.DEFAULT);
    private final AtomicReference<Countries> countries = new AtomicReference<>(COUNTRIES);
    private final Messages messages = new Messages(new YamlConfiguration(), Logger.getLogger("test"));
    private final Flags flags = new Flags(countries::get, style::get, () -> messages);

    private static PlayerProfile player(String country, Setting off) {
        int bits = Setting.defaults();
        if (off != null) bits = off.write(bits, false);
        return new PlayerProfile(1, UUID.randomUUID(), "P", bits, null, country, 0, Map.of());
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void theHeadWearsTheFlagTexture() {
        Component head = flags.flag("de");
        ObjectComponent object = assertInstanceOf(ObjectComponent.class, head);
        PlayerHeadObjectContents contents = assertInstanceOf(PlayerHeadObjectContents.class, object.contents());
        String textures = new String(java.util.Base64.getDecoder().decode(contents.profileProperties().getFirst().value()));
        assertTrue(textures.contains("http://textures.minecraft.net/texture/" + DE_FLAG), textures);
        assertTrue(contents.hat());
        assertEquals("DE", plain(object.fallback()), "the code where heads can't be drawn");
        assertSame(head, flags.flag("DE"), "cached");
        assertEquals(Component.empty(), flags.flag("BV"), "no flag in countries.yml");
        assertEquals(Component.empty(), flags.flag("XX"));
        assertEquals(Component.empty(), flags.flag(null));
        assertTrue(flags.hasFlag("DE"));
        assertFalse(flags.hasFlag("BV"));
    }

    @Test
    void formattedAddsTheSpace() {
        Component f = flags.formatted("DE");
        assertTrue(plain(f).endsWith(" "), "\"<flag> \"");
        assertEquals(Component.empty(), flags.formatted("BV"), "no flag, no space");
        style.set(new FlagStyle(true, "[<flag>]", true, EnumSet.allOf(Flags.Place.class)));
        assertTrue(plain(flags.formatted("DE")).startsWith("["), "a new gui.yml is picked up");
        assertFalse(plain(flags.formatted("DE")).endsWith(" "));
    }

    @Test
    void everySwitchApplies() {
        PlayerProfile german = player("DE", null);
        PlayerProfile viewer = player(null, null);
        assertNotEquals(Component.empty(), flags.flag(Flags.Place.CHAT, viewer, german));
        assertNotEquals(Component.empty(), flags.flag(Flags.Place.TAB, null, german), "no viewer (tab list)");
        assertEquals(Component.empty(), flags.flag(Flags.Place.CHAT, player(null, Setting.SHOW_FLAGS), german),
            "the viewer turned flags off");
        assertEquals(Component.empty(), flags.flag(Flags.Place.CHAT, viewer, player("DE", Setting.SHOW_MY_FLAG)),
            "the player hides their flag");
        assertEquals(Component.empty(), flags.flag(Flags.Place.CHAT, viewer, player(null, null)), "no country");
        assertEquals(Component.empty(), flags.flag(Flags.Place.CHAT, viewer, player("BV", null)), "no flag for it");
        assertEquals(Component.empty(), flags.flag(Flags.Place.CHAT, viewer, null));

        style.set(new FlagStyle(true, "<flag> ", true, EnumSet.complementOf(EnumSet.of(Flags.Place.CHAT))));
        assertEquals(Component.empty(), flags.flag(Flags.Place.CHAT, viewer, german), "gui.yml flags.show.chat: false");
        assertNotEquals(Component.empty(), flags.flag(Flags.Place.NAMETAG, viewer, german));

        style.set(new FlagStyle(false, "<flag> ", true, EnumSet.allOf(Flags.Place.class)));
        assertEquals(Component.empty(), flags.flag(Flags.Place.NAMETAG, viewer, german), "gui.yml flags.enabled: false");
        assertEquals(Component.empty(), flags.flag("DE"));
    }

    @Test
    void rowsWithoutAProfile() {
        PlayerProfile viewer = player(null, null);
        int shows = Setting.defaults();
        int hides = Setting.SHOW_MY_FLAG.write(shows, false);
        assertNotEquals(Component.empty(), flags.flag(Flags.Place.LEADERBOARD, viewer, "DE", shows));
        assertEquals(Component.empty(), flags.flag(Flags.Place.LEADERBOARD, viewer, "DE", hides));
        assertEquals(Component.empty(), flags.flag(Flags.Place.LEADERBOARD, player(null, Setting.SHOW_FLAGS), "DE", shows));
        assertEquals(Component.empty(), flags.flag(Flags.Place.LEADERBOARD, viewer, null, shows));
    }

    @Test
    void visibleCountryAndNames() {
        assertEquals("DE", flags.visibleCountry(player("DE", null)));
        assertNull(flags.visibleCountry(player("DE", Setting.SHOW_MY_FLAG)));
        assertEquals("BV", flags.visibleCountry(player("BV", null)), "a listed country without a flag is still visible");
        assertNull(flags.visibleCountry(player("XX", null)), "not in countries.yml");
        assertNull(flags.visibleCountry(null));
        assertEquals("Germany", flags.name("DE"));
        assertEquals("XX", flags.name("XX"));
        assertEquals("", flags.name(null));
        assertEquals("EU", flags.region("DE"));
        assertNull(flags.region("BV"));
        assertEquals("DE", flags.find("germany"));
        assertTrue(flags.isKnown("de"));

        countries.set(Countries.EMPTY);
        assertFalse(flags.isKnown("DE"), "a new countries.yml is picked up");
        assertEquals(Component.empty(), flags.flag("DE"));
    }
}
