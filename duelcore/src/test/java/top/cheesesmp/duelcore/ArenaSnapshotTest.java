package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.arena.ArenaSnapshot;
import top.cheesesmp.duelcore.arena.SchematicImporter;

class ArenaSnapshotTest {

    @Test
    void roundTripAndCompression() throws IOException {
        ArenaSnapshot.Builder b = new ArenaSnapshot.Builder(64, 32, 64);
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) b.set(x, 0, z, "minecraft:stone");
        b.set(5, 1, 7, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        b.set(6, 1, 7, "minecraft:cave_air");
        ArenaSnapshot s = b.build();
        assertEquals(3, s.palette().length);
        assertEquals(64 * 64 + 1, s.nonAirCount());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.write(out);
        assertTrue(out.size() < 2_000, "compressed size " + out.size());
        ArenaSnapshot r = ArenaSnapshot.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(64, r.sizeX());
        assertEquals(32, r.sizeY());
        assertEquals("minecraft:stone", r.palette()[r.get(10, 0, 10)]);
        assertEquals(0, r.get(6, 1, 7));
        assertTrue(r.palette()[r.get(5, 1, 7)].startsWith("minecraft:oak_stairs[facing=north"));
        for (int i = 0; i < s.volume(); i++) assertEquals(s.getAt(i), r.getAt(i));
    }

    @Test
    void importsSpongeV3() throws IOException {
        // 2x1x2 schematic: stone at (0,0,0) and (1,0,1)
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        palette.put("minecraft:stone", 1);
        byte[] data = {1, 0, 0, 1};
        ArenaSnapshot s = SchematicImporter.read(new ByteArrayInputStream(schem(3, 2, 1, 2, palette, data)));
        assertEquals("minecraft:stone", s.palette()[s.get(0, 0, 0)]);
        assertEquals(0, s.get(1, 0, 0));
        assertEquals("minecraft:stone", s.palette()[s.get(1, 0, 1)]);
        ArenaSnapshot s2 = SchematicImporter.read(new ByteArrayInputStream(schem(2, 2, 1, 2, palette, data)));
        assertEquals(2, s2.nonAirCount());
    }

    private static byte[] schem(int version, int w, int h, int l, Map<String, Integer> palette, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            str(out, version >= 3 ? "" : "Schematic");
            if (version >= 3) {
                out.writeByte(10);
                str(out, "Schematic");
            }
            out.writeByte(3); str(out, "Version"); out.writeInt(version);
            out.writeByte(2); str(out, "Width"); out.writeShort(w);
            out.writeByte(2); str(out, "Height"); out.writeShort(h);
            out.writeByte(2); str(out, "Length"); out.writeShort(l);
            if (version >= 3) {
                out.writeByte(10);
                str(out, "Blocks");
            }
            out.writeByte(10); str(out, "Palette");
            for (var e : palette.entrySet()) {
                out.writeByte(3);
                str(out, e.getKey());
                out.writeInt(e.getValue());
            }
            out.writeByte(0);
            out.writeByte(7); str(out, version >= 3 ? "Data" : "BlockData"); out.writeInt(data.length); out.write(data);
            if (version >= 3) out.writeByte(0); // end Blocks
            if (version >= 3) out.writeByte(0); // end Schematic
            out.writeByte(0); // end root
        }
        return bytes.toByteArray();
    }

    private static void str(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }
}
