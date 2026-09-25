package top.cheesesmp.duelcore.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.rating.Tier;

class HubProgressTest {

    @Test
    void ringOnlyForABetterOrFirstTier() {
        assertNull(HubProgress.better(Map.of(), Map.of()));
        assertNull(HubProgress.better(Map.of("sword", Tier.HT3), Map.of("sword", Tier.HT3)));
        assertNull(HubProgress.better(Map.of("sword", Tier.HT3), Map.of("sword", Tier.LT3))); // demotion
        assertNull(HubProgress.better(Map.of("sword", Tier.HT3, "axe", Tier.LT4), Map.of("sword", Tier.HT3))); // kit gone
        assertEquals(Tier.HT2, HubProgress.better(Map.of("sword", Tier.LT2), Map.of("sword", Tier.HT2)));
        assertEquals(Tier.LT5, HubProgress.better(Map.of(), Map.of("pot", Tier.LT5))); // placed
        // the best of several improvements
        assertEquals(Tier.MT1, HubProgress.better(Map.of("sword", Tier.LT1, "pot", Tier.LT4),
            Map.of("sword", Tier.MT1, "pot", Tier.HT4, "axe", Tier.HT5)));
    }
}
