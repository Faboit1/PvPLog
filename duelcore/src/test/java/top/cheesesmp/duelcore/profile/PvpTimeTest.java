package top.cheesesmp.duelcore.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PvpTimeTest {

    private static long ms(long h, long m, long s) {
        return ((h * 60 + m) * 60 + s) * 1000;
    }

    @Test
    void formatsHoursMinutesSeconds() {
        assertEquals("0m", PvpTime.format(0));
        assertEquals("0m", PvpTime.format(999));
        assertEquals("0m", PvpTime.format(-5000));
        assertEquals("0m 1s", PvpTime.format(1000));
        assertEquals("34m 5s", PvpTime.format(ms(0, 34, 5) + 400));
        assertEquals("59m 59s", PvpTime.format(ms(0, 59, 59)));
        assertEquals("1h 0m", PvpTime.format(ms(1, 0, 0)));
        assertEquals("12h 34m", PvpTime.format(ms(12, 34, 59)));
        assertEquals("250h 3m", PvpTime.format(ms(250, 3, 0)));
    }

    @Test
    void secondsAndHours() {
        assertEquals(0, PvpTime.seconds(-1));
        assertEquals(2045, PvpTime.seconds(ms(0, 34, 5) + 999));
        assertEquals("0.0", PvpTime.hours(0));
        assertEquals("0.5", PvpTime.hours(ms(0, 30, 0)));
        assertEquals("12.6", PvpTime.hours(ms(12, 34, 0)));
        assertEquals("100.0", PvpTime.hours(ms(100, 0, 0)));
    }

    @Test
    void profileKeepsPvpTimeAcrossSeasonReset() {
        PlayerProfile p = new PlayerProfile(1, new UUID(0, 1), "A", 0, null, null, 0, Map.of());
        assertEquals(0, p.pvpTimeMs());
        p.pvpTimeMs(60_000);
        p.addPvpTime(30_000);
        p.addPvpTime(-10); // a match without a fight stores 0; never subtracts
        assertEquals(90_000, p.pvpTimeMs());
        p.clearStats();
        assertEquals(90_000, p.pvpTimeMs());
        assertEquals("1m 30s", PvpTime.format(p.pvpTimeMs()));
    }
}
