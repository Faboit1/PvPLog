package top.cheesesmp.duelcore.match;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spectator alerts: every "stopped watching" follows a told "now watching", and spectating in a loop can't spam. */
class SpectatorAlertsTest {

    @Test
    void startAndStopAreToldInPairs() {
        SpectatorAlerts alerts = new SpectatorAlerts();
        UUID s = UUID.randomUUID();
        assertTrue(alerts.start(s, 0));
        assertTrue(alerts.stop(s));
        assertFalse(alerts.stop(s), "stopped once");
        assertFalse(alerts.stop(UUID.randomUUID()), "never started");
    }

    @Test
    void rejoiningWithinTheCooldownIsQuiet() {
        SpectatorAlerts alerts = new SpectatorAlerts();
        UUID s = UUID.randomUUID();
        assertTrue(alerts.start(s, 1_000));
        assertTrue(alerts.stop(s));
        // /spectate and /leave in a loop: nothing more for the rest of the cooldown, in or out
        for (long t = 2_000; t < 1_000 + SpectatorAlerts.COOLDOWN_MS; t += 1_000) {
            assertFalse(alerts.start(s, t), "start at " + t);
            assertFalse(alerts.stop(s), "stop at " + t);
        }
        assertTrue(alerts.start(s, 1_000 + SpectatorAlerts.COOLDOWN_MS));
        assertTrue(alerts.stop(s));
    }

    @Test
    void spectatorsAreCountedApart() {
        SpectatorAlerts alerts = new SpectatorAlerts();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(alerts.start(a, 0));
        assertTrue(alerts.start(b, 10));
        assertTrue(alerts.stop(b));
        assertTrue(alerts.stop(a));
    }
}
