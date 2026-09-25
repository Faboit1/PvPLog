package top.cheesesmp.duelcore.arena;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads Sponge schematic files (.schem, versions 2 and 3, as written by WorldEdit/FAWE) into an {@link ArenaSnapshot}.
 * Block entities (chest contents, sign text) are not imported; blocks and block states are exact.
 */
public final class SchematicImporter {

    private SchematicImporter() {
    }

    public static ArenaSnapshot read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    @SuppressWarnings("unchecked")
    public static ArenaSnapshot read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)));
        int type = in.readUnsignedByte();
        if (type != 10) throw new IOException("schematic root is not a compound");
        readString(in); // root name
        Map<String, Object> root = (Map<String, Object>) readPayload(in, 10, 0);
        Map<String, Object> schem = root.containsKey("Schematic") && root.get("Schematic") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : root;
        int version = number(schem.get("Version"), 2);
        int width = number(schem.get("Width"), -1) & 0xFFFF;
        int height = number(schem.get("Height"), -1) & 0xFFFF;
        int length = number(schem.get("Length"), -1) & 0xFFFF;
        Map<String, Object> palette;
        byte[] blockData;
        if (version >= 3) {
            Map<String, Object> blocks = (Map<String, Object>) schem.get("Blocks");
            if (blocks == null) throw new IOException("v3 schematic without Blocks");
            palette = (Map<String, Object>) blocks.get("Palette");
            blockData = (byte[]) blocks.get("Data");
        } else {
            palette = (Map<String, Object>) schem.get("Palette");
            blockData = (byte[]) schem.get("BlockData");
        }
        if (palette == null || blockData == null || width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("unsupported or empty schematic (version " + version + ")");
        }
        String[] byId = new String[palette.size()];
        for (Map.Entry<String, Object> e : palette.entrySet()) {
            int id = number(e.getValue(), -1);
            if (id < 0 || id >= byId.length) throw new IOException("bad palette id " + id);
            byId[id] = e.getKey();
        }
        ArenaSnapshot.Builder builder = new ArenaSnapshot.Builder(width, height, length);
        int pos = 0;
        int index = 0;
        int volume = width * height * length;
        while (pos < blockData.length && index < volume) {
            int value = 0;
            int shift = 0;
            int b;
            do {
                b = blockData[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < blockData.length);
            String state = value < byId.length && byId[value] != null ? byId[value] : ArenaSnapshot.AIR;
            int y = index / (width * length);
            int rem = index % (width * length);
            int z = rem / width;
            int x = rem % width;
            if (!state.equals(ArenaSnapshot.AIR)) builder.set(x, y, z, state);
            index++;
        }
        return builder.build();
    }

    private static int number(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = in.readNBytes(len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nested too deep");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int len = in.readInt();
                if (len < 0 || len > 256 * 1024 * 1024) throw new IOException("byte array too large");
                yield in.readNBytes(len);
            }
            case 8 -> readString(in);
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(len, 4096)));
                for (int i = 0; i < len; i++) list.add(readPayload(in, elementType, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = readString(in);
                    map.put(name, readPayload(in, t, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                yield arr;
            }
            case 12 -> {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                yield arr;
            }
            default -> throw new IOException("unknown NBT tag " + type);
        };
    }
}
