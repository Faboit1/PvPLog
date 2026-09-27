package top.cheesesmp.duelcore.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The IP → country table: address parsing (no DNS), lookups at range edges, merging, gaps, bad lines. */
class IpTableTest {

    private static IpTable table(String... lines) {
        IpTable.Builder b = new IpTable.Builder();
        for (String line : lines) b.line(line);
        return b.build();
    }

    @Test
    void parsesIpv4ByHand() {
        assertEquals(0L, IpTable.parseV4("0.0.0.0"));
        assertEquals(0xFFFF_FFFFL, IpTable.parseV4("255.255.255.255"));
        assertEquals((81L << 24) | (2 << 16) | (69 << 8) | 142, IpTable.parseV4("81.2.69.142"));
        assertEquals(IpTable.parseV4("1.2.3.4"), IpTable.parseV4("001.002.003.004"));
        for (String bad : List.of("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1..2.3", ".1.2.3", "1.2.3.", "1.2.3.4 ", "a.b.c.d",
            "1.2.3.-4", "1234.1.1.1", "localhost", "example.com", "١.٢.٣.٤")) {
            assertEquals(-1L, IpTable.parseV4(bad), bad);
        }
    }

    @Test
    void parsesIpv6ByHand() {
        assertArrayEquals(new long[] {0, 0}, IpTable.parseV6("::"));
        assertArrayEquals(new long[] {0, 1}, IpTable.parseV6("::1"));
        assertArrayEquals(new long[] {0x0001_0000_0000_0000L, 0}, IpTable.parseV6("1::"));
        assertArrayEquals(new long[] {0x2001_0db8_0000_0000L, 0x0000_0000_0000_0001L}, IpTable.parseV6("2001:db8::1"));
        assertArrayEquals(new long[] {0x2001_0db8_0000_0000L, 0x0000_0000_0000_0001L}, IpTable.parseV6("2001:0DB8:0:0:0:0:0:1"));
        assertArrayEquals(new long[] {-1L, -1L}, IpTable.parseV6("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));
        assertArrayEquals(new long[] {0x0001_0002_0003_0004L, 0x0005_0006_0007_0000L}, IpTable.parseV6("1:2:3:4:5:6:7::"));
        // the last 32 bits written as IPv4
        assertArrayEquals(new long[] {0, 0x0000_ffff_0102_0304L}, IpTable.parseV6("::ffff:1.2.3.4"));
        assertArrayEquals(new long[] {0x0001_0002_0003_0004L, 0x0005_0006_0102_0304L}, IpTable.parseV6("1:2:3:4:5:6:1.2.3.4"));
        for (String bad : List.of(":", ":::", "1:::2", "1::2::3", ":1::", "1:2", "1:2:3:4:5:6:7:8:9", "1::2:3:4:5:6:7:8",
            "12345::", "g::", "1:", "::1%eth0", "fe80::1%1", "::1.2.3", "1:2:3:4:5:6:7:1.2.3.4", "::256.1.1.1", "1.2.3.4",
            "::ffff:1.2.3.4:5")) {
            assertNull(IpTable.parseV6(bad), bad);
        }
    }

    @Test
    void findsTheCountryAtEveryEdge() {
        IpTable t = table("1.0.0.0,1.0.0.255,AU", "1.0.1.0,1.0.3.255,CN", "8.8.8.0,8.8.8.255,US");
        assertNull(t.lookup("0.255.255.255"), "before the first range");
        assertEquals("AU", t.lookup("1.0.0.0"));
        assertEquals("AU", t.lookup("1.0.0.255"));
        assertEquals("CN", t.lookup("1.0.1.0"));
        assertEquals("CN", t.lookup("1.0.3.255"));
        assertNull(t.lookup("1.0.4.0"), "a gap");
        assertNull(t.lookup("8.8.7.255"));
        assertEquals("US", t.lookup("8.8.8.8"));
        assertNull(t.lookup("8.8.9.0"), "after the last range");
        assertNull(t.lookup("255.255.255.255"));
        assertNull(t.lookup("not an address"));
    }

    @Test
    void theTopAndBottomAddresses() {
        IpTable t = table("0.0.0.0,0.255.255.255,ZZ", "1.0.0.0,127.255.255.255,DE", "128.0.0.0,255.255.255.255,FR",
            "::,::ffff,ZZ", "::1:0,7fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,DE", "8000::,ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,FR");
        assertNull(t.lookup("0.0.0.1"));
        assertEquals("DE", t.lookup("127.255.255.255"));
        assertEquals("FR", t.lookup("128.0.0.0"), "the sign bit");
        assertEquals("FR", t.lookup("255.255.255.255"));
        assertEquals("DE", t.lookup("7fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));
        assertEquals("FR", t.lookup("8000::"), "the sign bit");
        assertEquals("FR", t.lookup("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));
        assertNull(t.lookup("::1"));
        assertEquals("DE", t.lookup("::1:0"));
    }

    @Test
    void ipv6RangesCarryOverTheLowHalf() {
        IpTable t = table("2001:db8::,2001:db8:0:0:ffff:ffff:ffff:ffff,NL", "2001:db8:0:1::,2001:db8:0:1:ffff:ffff:ffff:ffff,BE",
            "2a00::,2a00:ffff:ffff:ffff:ffff:ffff:ffff:ffff,GB");
        assertEquals("NL", t.lookup("2001:db8::1"));
        assertEquals("NL", t.lookup("2001:db8::ffff:ffff:ffff:ffff"));
        assertEquals("BE", t.lookup("2001:db8:0:1::"), "end + 1 carries into the high half");
        assertNull(t.lookup("2001:db8:0:2::"));
        assertEquals("GB", t.lookup("2a00:1450::1"));
        assertNull(t.lookup("2a01::"));
        assertEquals(3, t.v6Ranges());
        assertEquals(0, t.v4Ranges());
    }

    @Test
    void neighboursOfOneCountryAreMerged() {
        IpTable t = table("1.0.0.0,1.0.0.255,AU", "1.0.1.0,1.0.1.255,AU", "1.0.2.0,1.0.2.255,AU", "1.0.3.0,1.0.3.255,CN",
            "1.0.5.0,1.0.5.255,CN");
        assertEquals(3, t.v4Ranges(), "AU merged into one, CN twice (there's a gap between)");
        assertEquals("AU", t.lookup("1.0.2.200"));
        assertEquals("CN", t.lookup("1.0.3.1"));
        assertNull(t.lookup("1.0.4.1"));
        assertEquals("CN", t.lookup("1.0.5.1"));
    }

    @Test
    void unknownRangesAreGaps() {
        IpTable t = table("9.0.0.0,9.255.255.255,US", "10.0.0.0,10.255.255.255,ZZ", "11.0.0.0,11.255.255.255,US");
        assertEquals("US", t.lookup("9.1.1.1"));
        assertNull(t.lookup("10.1.1.1"));
        assertEquals("US", t.lookup("11.1.1.1"));
        assertEquals(2, t.v4Ranges(), "not merged across the ZZ range");
    }

    @Test
    void anyOrderAndOverlapsWork() {
        IpTable t = table("5.0.0.0,5.0.0.255,FR", "1.0.0.0,1.0.0.255,AU", "3.0.0.0,3.0.0.255,DE",
            "3.0.0.128,3.0.1.255,PL", // overlaps DE: the first range keeps its part
            "3.0.0.10,3.0.0.20,IT"); // inside DE: ignored
        assertEquals("AU", t.lookup("1.0.0.1"));
        assertEquals("DE", t.lookup("3.0.0.15"));
        assertEquals("DE", t.lookup("3.0.0.255"));
        assertEquals("PL", t.lookup("3.0.1.0"));
        assertEquals("FR", t.lookup("5.0.0.5"));
        assertNull(t.lookup("4.0.0.0"));
    }

    @Test
    void badLinesAreSkippedAndCounted() {
        IpTable.Builder b = new IpTable.Builder();
        assertTrue(b.line("# a comment"));
        assertTrue(b.line("   "));
        assertFalse(b.line("ip_start,ip_end,country"), "a header");
        assertFalse(b.line("1.0.0.0,1.0.0.255"), "two fields");
        assertFalse(b.line("1.0.0.0,1.0.0.255,AU,extra"), "four fields");
        assertFalse(b.line("1.0.0.0,1.0.0.255,AUS"), "three letters");
        assertFalse(b.line("1.0.0.0,1.0.0.255,A1"), "not letters");
        assertFalse(b.line("1.0.0.0,,AU"));
        assertFalse(b.line("1.0.0.255,1.0.0.0,AU"), "start after end");
        assertFalse(b.line("1.0.0.0,::1,AU"), "mixed families");
        assertFalse(b.line("::1,1.0.0.0,AU"), "mixed families");
        assertFalse(b.line("example.com,example.org,AU"), "host names are never resolved");
        assertTrue(b.line("\"2.0.0.0\",\"2.0.0.255\",\"de\""), "quoted, lower case");
        assertTrue(b.line("﻿3.0.0.0,3.0.0.255,FR"), "a byte order mark");
        assertEquals(2, b.accepted());
        assertEquals(10, b.skipped());
        IpTable t = b.build();
        assertEquals("DE", t.lookup("2.0.0.9"));
        assertEquals("FR", t.lookup("3.0.0.9"));
    }

    @Test
    void readsAStreamWithAnyLineEndsAndSkipsHugeLines() throws IOException {
        String longLine = "9".repeat(IpTable.MAX_LINE + 50) + ",1.1.1.1,US";
        String csv = "1.0.0.0,1.0.0.255,AU\r\n" + longLine + "\n2.0.0.0,2.0.0.255,DE\r3.0.0.0,3.0.0.255,FR";
        IpTable.Builder b = new IpTable.Builder().read(new StringReader(csv));
        assertEquals(3, b.accepted());
        assertEquals(1, b.skipped(), "the long line");
        IpTable t = b.build();
        assertEquals("AU", t.lookup("1.0.0.1"));
        assertEquals("DE", t.lookup("2.0.0.1"));
        assertEquals("FR", t.lookup("3.0.0.1"), "the last line has no line end");
    }

    @Test
    void refusesStreamsWithTooManyLines() {
        String lines = "\n".repeat(IpTable.MAX_LINES + 1);
        assertThrows(IOException.class, () -> new IpTable.Builder().read(new StringReader(lines)));
    }

    @Test
    void looksUpInetAddressesWithoutDns() throws Exception {
        IpTable t = table("81.2.69.0,81.2.69.255,GB", "2a02:c7f::,2a02:c7f:ffff:ffff:ffff:ffff:ffff:ffff,GB");
        assertEquals("GB", t.lookup(InetAddress.getByAddress(new byte[] {81, 2, 69, (byte) 142})));
        byte[] v6 = new byte[16];
        v6[0] = 0x2a;
        v6[1] = 0x02;
        v6[2] = 0x0c;
        v6[3] = 0x7f;
        v6[15] = 1;
        assertEquals("GB", t.lookup(InetAddress.getByAddress(v6)));
        assertNull(t.lookup((InetAddress) null));
        assertTrue(IpTable.EMPTY.isEmpty());
        assertNull(IpTable.EMPTY.lookup("81.2.69.142"));
        assertNull(IpTable.EMPTY.lookup("2a02:c7f::1"));
    }

    @Test
    void readsGzippedAndPlainFiles(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String csv = "1.0.0.0,1.0.0.255,AU\n2a00::,2a00:ffff:ffff:ffff:ffff:ffff:ffff:ffff,GB\n";
        Path plain = dir.resolve("plain.csv");
        Files.writeString(plain, csv);
        Path gz = dir.resolve("db.csv.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(csv.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        for (Path p : List.of(plain, gz)) {
            IpTable t = GeoIpService.read(p);
            assertEquals("AU", t.lookup("1.0.0.7"), p.toString());
            assertEquals("GB", t.lookup("2a00::7"), p.toString());
        }
    }

    /**
     * The real database (not in the repository): {@code DBIP_CSV_GZ=/path/dbip-country-lite.csv.gz ./gradlew test}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "DBIP_CSV_GZ", matches = ".+")
    void readsTheRealDatabase() throws IOException {
        Path file = Path.of(System.getenv("DBIP_CSV_GZ"));
        long start = System.nanoTime();
        IpTable t = GeoIpService.read(file);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("db-ip: " + t + " in " + ms + " ms");
        assertTrue(t.v4Ranges() > GeoIpService.MIN_V4_RANGES);
        assertTrue(t.v6Ranges() > 10_000);
        assertEquals("US", t.lookup("8.8.8.8"));
        assertEquals("AU", t.lookup("1.1.1.1"));
        assertNull(t.lookup("10.1.2.3"));
        assertNull(t.lookup("192.168.1.1"));
        assertNull(t.lookup("127.0.0.1"));
        assertNull(t.lookup("fc00::1"));
        // every 101st line: its first and last address (and one in between) have the line's country
        List<String> lines;
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(
            new java.util.zip.GZIPInputStream(Files.newInputStream(file)), java.nio.charset.StandardCharsets.US_ASCII))) {
            lines = in.lines().toList();
        }
        int checked = 0;
        for (int i = 0; i < lines.size(); i += 101) {
            String[] f = lines.get(i).split(",");
            String expected = f[2].equals("ZZ") ? null : f[2];
            assertEquals(expected, t.lookup(f[0]), lines.get(i));
            assertEquals(expected, t.lookup(f[1]), lines.get(i));
            if (f[0].indexOf(':') < 0) {
                long mid = (IpTable.parseV4(f[0]) + IpTable.parseV4(f[1])) / 2;
                assertEquals(expected, t.lookupV4((int) mid), lines.get(i));
            }
            checked++;
        }
        assertTrue(checked > 7000, "checked " + checked);
    }
}
