package top.cheesesmp.duelcore.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.profile.Setting;

/** The pure parts of the GeoIP service and the flag settings: which addresses are looked up, the monthly URL. */
class GeoIpTest {

    private static InetAddress v4(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static InetAddress v6(int first, int second, int last) throws Exception {
        byte[] b = new byte[16];
        b[0] = (byte) (first >> 8);
        b[1] = (byte) first;
        b[2] = (byte) (second >> 8);
        b[3] = (byte) second;
        b[15] = (byte) last;
        return InetAddress.getByAddress(b);
    }

    @Test
    void onlyPublicAddressesAreLookedUp() throws Exception {
        assertTrue(GeoIpService.isPublic(v4(81, 2, 69, 142)));
        assertTrue(GeoIpService.isPublic(v4(8, 8, 8, 8)));
        assertTrue(GeoIpService.isPublic(v4(100, 63, 0, 1)), "just below carrier-grade NAT");
        assertTrue(GeoIpService.isPublic(v4(100, 128, 0, 1)), "just above carrier-grade NAT");
        assertTrue(GeoIpService.isPublic(v6(0x2a02, 0x0c7f, 1)));
        for (InetAddress a : List.of(v4(127, 0, 0, 1), v4(10, 1, 2, 3), v4(172, 16, 0, 1), v4(172, 31, 255, 255),
            v4(192, 168, 1, 1), v4(169, 254, 1, 1), v4(100, 64, 0, 1), v4(100, 127, 255, 255), v4(0, 0, 0, 0),
            v4(0, 1, 2, 3), v4(224, 0, 0, 1), v6(0, 0, 1), v6(0, 0, 0), v6(0xfe80, 0, 1), v6(0xfc00, 0, 1), v6(0xfd12, 0x3456, 1),
            v6(0xfec0, 0, 1), v6(0xff02, 0, 1))) {
            assertFalse(GeoIpService.isPublic(a), a.toString());
        }
        // an IPv4-mapped IPv6 address comes as IPv4
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 192;
        mapped[13] = (byte) 168;
        mapped[15] = 1;
        assertFalse(GeoIpService.isPublic(InetAddress.getByAddress(mapped)));
    }

    @Test
    void monthlyUrls() {
        assertEquals("https://download.db-ip.com/free/dbip-country-lite-2026-09.csv.gz",
            GeoIpService.url(GeoIpService.DEFAULT_URL, YearMonth.of(2026, 9)));
        assertEquals("https://download.db-ip.com/free/dbip-country-lite-2027-12.csv.gz",
            GeoIpService.url(GeoIpService.DEFAULT_URL, YearMonth.of(2027, 12)));
        assertEquals("https://example.org/fixed.csv.gz", GeoIpService.url("https://example.org/fixed.csv.gz", YearMonth.of(2026, 1)));
        // January falls back to December of the year before
        assertEquals("https://x/2025/12", GeoIpService.url("https://x/{year}/{month}", YearMonth.of(2026, 1).minusMonths(1)));
    }

    @Test
    void flagStyleFromGuiYml() throws Exception {
        YamlConfiguration empty = new YamlConfiguration();
        FlagStyle defaults = FlagStyle.parse(empty);
        assertEquals(FlagStyle.DEFAULT, defaults);
        for (Flags.Place p : Flags.Place.values()) assertTrue(defaults.shows(p), p.id());

        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("flags:\n  format: \"<gray>[</gray><flag><gray>]</gray>\"\n  hat: false\n  show:\n    tab: false\n    chat: false\n");
        FlagStyle s = FlagStyle.parse(y);
        assertFalse(s.shows(Flags.Place.TAB));
        assertFalse(s.shows(Flags.Place.CHAT));
        assertTrue(s.shows(Flags.Place.NAMETAG));
        assertFalse(s.hat());
        assertEquals("<gray>[</gray><flag><gray>]</gray>", s.format());

        y.loadFromString("flags:\n  enabled: false\n");
        for (Flags.Place p : Flags.Place.values()) assertFalse(FlagStyle.parse(y).shows(p), p.id());
        assertEquals(EnumSet.allOf(Flags.Place.class), FlagStyle.parse(y).places(), "only switched off as a whole");
    }

    @Test
    void flagSettingsDefaultOnAndNeedNoMigration() {
        for (Setting s : List.of(Setting.AUTO_COUNTRY, Setting.SHOW_FLAGS, Setting.SHOW_MY_FLAG)) {
            assertTrue(s.relative(), s.name());
            assertTrue(s.defaultValue(), s.name());
            assertTrue(s.read(0), s + " is on in a row from before it existed");
        }
        assertEquals(22, Setting.AUTO_COUNTRY.bit());
        assertEquals(23, Setting.SHOW_FLAGS.bit());
        assertEquals(24, Setting.SHOW_MY_FLAG.bit());
        assertTrue(Flags.showsOwn(Setting.defaults()));
        assertFalse(Flags.showsOwn(Setting.SHOW_MY_FLAG.write(Setting.defaults(), false)));
        assertTrue(Flags.wantsFlags(null), "the console and unknown viewers");
    }
}
