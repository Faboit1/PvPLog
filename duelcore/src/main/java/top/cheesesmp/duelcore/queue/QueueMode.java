package top.cheesesmp.duelcore.queue;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

public enum QueueMode {
    RANKED,
    UNRANKED,
    /** 2v2 party queue (always unranked). */
    PARTY;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable QueueMode parse(@Nullable String raw) {
        if (raw == null) return null;
        for (QueueMode m : values()) if (m.id().equalsIgnoreCase(raw.trim())) return m;
        return null;
    }
}
