package top.cheesesmp.duelcore.arena;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A block-exact copy of an arena region: a palette of block-state strings plus one palette index per block.
 * Index order is x fastest, then z, then y ({@code (y * sizeZ + z) * sizeX + x}). Palette entry 0 is always air.
 *
 * <p>File format ({@code .dca}, gzip): magic "DCA1", sizes, palette (UTF strings), then run-length pairs
 * (varint run, varint palette index). Mostly-air arenas compress to a few KB.
 */
public final class ArenaSnapshot {

    public static final String AIR = "minecraft:air";
    private static final int MAGIC = 0x44434131; // "DCA1"

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String[] palette;
    private final char[] data;

    public ArenaSnapshot(int sizeX, int sizeY, int sizeZ, String[] palette, char[] data) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("empty snapshot");
        if ((long) sizeX * sizeY * sizeZ != data.length) throw new IllegalArgumentException("size mismatch");
        if (palette.length == 0 || !AIR.equals(palette[0])) throw new IllegalArgumentException("palette[0] must be air");
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.data = data;
    }

    /** An all-air snapshot of the given size (used to clear a slot). */
    public static ArenaSnapshot empty(int sizeX, int sizeY, int sizeZ) {
        return new ArenaSnapshot(sizeX, sizeY, sizeZ, new String[] {AIR}, new char[sizeX * sizeY * sizeZ]);
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public String[] palette() {
        return palette;
    }

    public int volume() {
        return data.length;
    }

    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    /** Palette index at a relative position. */
    public int get(int x, int y, int z) {
        return data[index(x, y, z)];
    }

    public int getAt(int flatIndex) {
        return data[flatIndex];
    }

    public long nonAirCount() {
        long n = 0;
        for (char c : data) if (c != 0) n++;
        return n;
    }

    /** Builder that interns block-state strings into a palette. */
    public static final class Builder {
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final char[] data;
        private final Map<String, Integer> ids = new HashMap<>();
        private String[] palette = new String[16];
        private int paletteSize;

        public Builder(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.data = new char[sizeX * sizeY * sizeZ];
            id(AIR);
        }

        public Builder set(int x, int y, int z, String blockState) {
            data[(y * sizeZ + z) * sizeX + x] = (char) id(blockState);
            return this;
        }

        private int id(String state) {
            String key = normalize(state);
            Integer existing = ids.get(key);
            if (existing != null) return existing;
            if (paletteSize == Character.MAX_VALUE) throw new IllegalStateException("palette too large");
            if (paletteSize == palette.length) palette = Arrays.copyOf(palette, palette.length * 2);
            palette[paletteSize] = key;
            ids.put(key, paletteSize);
            return paletteSize++;
        }

        public ArenaSnapshot build() {
            return new ArenaSnapshot(sizeX, sizeY, sizeZ, Arrays.copyOf(palette, paletteSize), data);
        }
    }

    /** Air variants (cave_air, void_air) collapse to plain air so resets never fight over them. */
    static String normalize(String state) {
        if (state.equals("minecraft:cave_air") || state.equals("minecraft:void_air")) return AIR;
        return state;
    }

    public void write(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp)) {
            write(raw);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void write(OutputStream raw) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(raw);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gzip));
        out.writeInt(MAGIC);
        out.writeInt(sizeX);
        out.writeInt(sizeY);
        out.writeInt(sizeZ);
        out.writeInt(palette.length);
        for (String s : palette) out.writeUTF(s);
        int i = 0;
        while (i < data.length) {
            char v = data[i];
            int run = 1;
            while (i + run < data.length && data[i + run] == v) run++;
            writeVarInt(out, run);
            writeVarInt(out, v);
            i += run;
        }
        out.flush();
        gzip.finish();
    }

    public static ArenaSnapshot read(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            return read(raw);
        }
    }

    public static ArenaSnapshot read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)));
        if (in.readInt() != MAGIC) throw new IOException("not a DuelCore arena snapshot");
        int sx = in.readInt();
        int sy = in.readInt();
        int sz = in.readInt();
        long volume = (long) sx * sy * sz;
        if (sx <= 0 || sy <= 0 || sz <= 0 || volume > 64L * 1024 * 1024) throw new IOException("bad snapshot size");
        int paletteSize = in.readInt();
        if (paletteSize <= 0 || paletteSize > Character.MAX_VALUE) throw new IOException("bad palette size");
        String[] palette = new String[paletteSize];
        for (int i = 0; i < paletteSize; i++) palette[i] = in.readUTF();
        char[] data = new char[(int) volume];
        int i = 0;
        while (i < data.length) {
            int run = readVarInt(in);
            int v = readVarInt(in);
            if (run <= 0 || i + run > data.length || v >= paletteSize) throw new IOException("corrupt snapshot data");
            Arrays.fill(data, i, i + run, (char) v);
            i += run;
        }
        return new ArenaSnapshot(sx, sy, sz, palette, data);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) throw new IOException("varint too long");
        }
    }
}
