package top.cheesesmp.duelcore.db;

import java.nio.ByteBuffer;
import java.util.UUID;

/** 16-byte binary UUID encoding for compact storage. */
public final class Uuids {

    private Uuids() {
    }

    public static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();
    }

    public static UUID fromBytes(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
