package top.cheesesmp.duelcore.geo;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Country by IP address: the ranges of a db-ip.com "IP to Country" CSV ({@code start,end,CC} per line, IPv4 and
 * IPv6) as sorted range starts with the country of each, found by binary search. Pure (no Bukkit, and addresses are
 * parsed by hand, so nothing ever triggers a DNS lookup), immutable and safe to read from any thread;
 * {@link GeoIpService} builds a new one off the main thread and swaps it in.
 *
 * <p>Each address family is a list of boundaries: from a start on, every address up to the next start belongs to
 * that start's country, or to none. Neighbouring ranges of the same country are merged into one boundary; gaps
 * (addresses no line covers, and {@code ZZ} "unknown" lines such as private networks) are boundaries without a
 * country. IPv4 addresses are ints and IPv6 addresses two longs (high and low 64 bits), all stored with the sign bit
 * flipped so that plain signed comparison is address order. The real database (about 717k lines) reads in about a
 * second and takes about 10 MB; a lookup takes well under a microsecond.
 */
public final class IpTable {

    /** Lines longer than this are skipped (a real line is under 90 characters). */
    static final int MAX_LINE = 200;
    /** A file with more lines than this is refused (the real one has well under a million). */
    static final int MAX_LINES = 5_000_000;
    /** A file with more characters than this is refused (the real one has about 30 million). */
    static final long MAX_CHARS = 400_000_000L;

    /** No ranges: every lookup is null. */
    public static final IpTable EMPTY = new IpTable(new int[0], new short[0], new long[0], new long[0], new short[0]);

    /** Country codes by their packed number ({@link #pack}); index 0 is "no country". */
    private static final String[] CODES = new String[26 * 26 + 1];

    static {
        for (int a = 0; a < 26; a++) {
            for (int b = 0; b < 26; b++) CODES[a * 26 + b + 1] = "" + (char) ('A' + a) + (char) ('A' + b);
        }
    }

    private final int[] v4;
    private final short[] v4Country;
    private final long[] v6Hi;
    private final long[] v6Lo;
    private final short[] v6Country;
    private final int v4Ranges;
    private final int v6Ranges;

    private IpTable(int[] v4, short[] v4Country, long[] v6Hi, long[] v6Lo, short[] v6Country) {
        this.v4 = v4;
        this.v4Country = v4Country;
        this.v6Hi = v6Hi;
        this.v6Lo = v6Lo;
        this.v6Country = v6Country;
        this.v4Ranges = countries(v4Country);
        this.v6Ranges = countries(v6Country);
    }

    private static int countries(short[] codes) {
        int n = 0;
        for (short c : codes) if (c != 0) n++;
        return n;
    }

    // ------------------------------------------------------------------ lookups

    /** The country of an address (two upper-case letters), or null when unknown. */
    public @Nullable String lookup(@Nullable InetAddress address) {
        if (address == null) return null;
        byte[] b = address.getAddress();
        if (b.length == 4) return lookupV4(readInt(b, 0));
        if (b.length == 16) return lookupV6(readLong(b, 0), readLong(b, 8));
        return null;
    }

    /** The country of an address literal ("81.2.69.142", "2a02:c7f::1"), or null (also when it isn't one). */
    public @Nullable String lookup(String literal) {
        String s = literal.trim();
        if (s.indexOf(':') >= 0) {
            long[] v6 = parseV6(s);
            return v6 == null ? null : lookupV6(v6[0], v6[1]);
        }
        long v4 = parseV4(s);
        return v4 < 0 ? null : lookupV4((int) v4);
    }

    /** The country of an IPv4 address (its 32 bits as an int), or null. */
    public @Nullable String lookupV4(int address) {
        int i = Arrays.binarySearch(v4, address ^ Integer.MIN_VALUE);
        if (i < 0) i = -i - 2; // the last start below the address
        return i < 0 ? null : CODES[v4Country[i]];
    }

    /** The country of an IPv6 address (its high and low 64 bits), or null. */
    public @Nullable String lookupV6(long hi, long lo) {
        long h = hi ^ Long.MIN_VALUE;
        long l = lo ^ Long.MIN_VALUE;
        int low = 0;
        int high = v6Hi.length - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int c = v6Hi[mid] != h ? Long.compare(v6Hi[mid], h) : Long.compare(v6Lo[mid], l);
            if (c <= 0) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found < 0 ? null : CODES[v6Country[found]];
    }

    /** Ranges with a country after merging (IPv4). */
    public int v4Ranges() {
        return v4Ranges;
    }

    /** Ranges with a country after merging (IPv6). */
    public int v6Ranges() {
        return v6Ranges;
    }

    public boolean isEmpty() {
        return v4Ranges == 0 && v6Ranges == 0;
    }

    // ------------------------------------------------------------------ reading

    /** Reads a whole CSV (see {@link Builder#line}). */
    public static IpTable read(Reader in) throws IOException {
        return new Builder().read(in).build();
    }

    /**
     * Collects CSV lines and builds the table. Lines are {@code start,end,CC}, with IPv4 or IPv6 addresses (both of
     * the same family), optionally quoted; blank lines and lines starting with # are ignored, anything else that
     * doesn't read (a header, a bad address, start after end, a country that isn't two letters) is skipped and
     * counted. {@code ZZ} marks addresses without a country. Lines may come in any order and may overlap: the first
     * range (lowest start) wins where they do.
     */
    public static final class Builder {

        private final Family v4 = new Family(false);
        private final Family v6 = new Family(true);
        private int accepted;
        private int skipped;

        /** Adds one line; false when it was skipped. */
        public boolean line(String raw) {
            String line = raw.strip();
            if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1).strip(); // byte order mark
            if (line.isEmpty() || line.charAt(0) == '#') return true;
            int a = line.indexOf(',');
            int b = a < 0 ? -1 : line.indexOf(',', a + 1);
            if (b < 0 || line.indexOf(',', b + 1) >= 0) return skip();
            String start = unquote(line.substring(0, a));
            String end = unquote(line.substring(a + 1, b));
            short country = country(unquote(line.substring(b + 1)));
            if (country < 0) return skip();
            if (start.indexOf(':') >= 0) {
                long[] s = parseV6(start);
                long[] e = end.indexOf(':') >= 0 ? parseV6(end) : null;
                if (s == null || e == null || compare(s[0], s[1], e[0], e[1]) > 0) return skip();
                v6.add(s[0], s[1], e[0], e[1], country);
            } else {
                long s = parseV4(start);
                long e = parseV4(end);
                if (s < 0 || e < 0 || s > e) return skip();
                v4.add(0, s, 0, e, country);
            }
            accepted++;
            return true;
        }

        private boolean skip() {
            skipped++;
            return false;
        }

        /**
         * Adds every line of {@code in}. Lines longer than {@value IpTable#MAX_LINE} characters are skipped; a stream
         * with more than {@value IpTable#MAX_LINES} lines or {@value IpTable#MAX_CHARS} characters is refused.
         */
        public Builder read(Reader in) throws IOException {
            char[] buf = new char[16384];
            StringBuilder line = new StringBuilder(96);
            boolean tooLong = false;
            long chars = 0;
            int lines = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                chars += n;
                if (chars > MAX_CHARS) throw new IOException("more than " + MAX_CHARS + " characters");
                for (int i = 0; i < n; i++) {
                    char c = buf[i];
                    if (c == '\n' || c == '\r') {
                        if (tooLong) skipped++;
                        else if (!line.isEmpty()) line(line.toString());
                        line.setLength(0);
                        tooLong = false;
                        if (++lines > MAX_LINES) throw new IOException("more than " + MAX_LINES + " lines");
                    } else if (!tooLong) {
                        if (line.length() >= MAX_LINE) {
                            tooLong = true;
                            line.setLength(0);
                        } else {
                            line.append(c);
                        }
                    }
                }
            }
            if (tooLong) skipped++;
            else if (!line.isEmpty()) line(line.toString());
            return this;
        }

        /** Lines read as ranges (ZZ ones included). */
        public int accepted() {
            return accepted;
        }

        /** Lines that could not be read. */
        public int skipped() {
            return skipped;
        }

        public IpTable build() {
            Boundaries b4 = v4.boundaries();
            Boundaries b6 = v6.boundaries();
            int[] starts4 = new int[b4.size];
            for (int i = 0; i < b4.size; i++) starts4[i] = ((int) b4.lo[i]) ^ Integer.MIN_VALUE;
            long[] hi6 = new long[b6.size];
            long[] lo6 = new long[b6.size];
            for (int i = 0; i < b6.size; i++) {
                hi6[i] = b6.hi[i] ^ Long.MIN_VALUE;
                lo6[i] = b6.lo[i] ^ Long.MIN_VALUE;
            }
            return new IpTable(starts4, Arrays.copyOf(b4.country, b4.size), hi6, lo6, Arrays.copyOf(b6.country, b6.size));
        }

        private static String unquote(String field) {
            String s = field.strip();
            return s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"' ? s.substring(1, s.length() - 1).strip() : s;
        }
    }

    /** The country's packed number, 0 for ZZ (no country), -1 when it isn't two letters. */
    static short country(String code) {
        if (code.length() != 2) return -1;
        char a = Character.toUpperCase(code.charAt(0));
        char b = Character.toUpperCase(code.charAt(1));
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return -1;
        return a == 'Z' && b == 'Z' ? 0 : pack(a, b);
    }

    private static short pack(char a, char b) {
        return (short) ((a - 'A') * 26 + (b - 'A') + 1);
    }

    /** Range starts and their countries of one family, merged, in address order. */
    private record Boundaries(long[] hi, long[] lo, short[] country, int size) {
    }

    /** The ranges of one address family while reading (IPv4 ranges keep their high halves at 0). */
    private static final class Family {

        private final boolean v6;
        private long[] startHi = new long[0];
        private long[] startLo = new long[0];
        private long[] endHi = new long[0];
        private long[] endLo = new long[0];
        private short[] country = new short[0];
        private int size;
        private boolean sorted = true;

        Family(boolean v6) {
            this.v6 = v6;
        }

        void add(long sHi, long sLo, long eHi, long eLo, short cc) {
            if (size == startLo.length) {
                int cap = Math.max(1024, size * 2);
                startLo = Arrays.copyOf(startLo, cap);
                endLo = Arrays.copyOf(endLo, cap);
                country = Arrays.copyOf(country, cap);
                if (v6) {
                    startHi = Arrays.copyOf(startHi, cap);
                    endHi = Arrays.copyOf(endHi, cap);
                }
            }
            if (size > 0 && compare(sHi, sLo, hi(startHi, size - 1), startLo[size - 1]) < 0) sorted = false;
            startLo[size] = sLo;
            endLo[size] = eLo;
            country[size] = cc;
            if (v6) {
                startHi[size] = sHi;
                endHi[size] = eHi;
            }
            size++;
        }

        private long hi(long[] array, int i) {
            return v6 ? array[i] : 0;
        }

        /**
         * The boundaries: every range's start (clipped behind the previous range where they overlap), a country-less
         * boundary wherever a gap starts, and nothing where the country stays the same (merged).
         */
        Boundaries boundaries() {
            int[] order = null;
            if (!sorted) {
                Integer[] boxed = new Integer[size];
                for (int i = 0; i < size; i++) boxed[i] = i;
                // a stable sort: of two ranges with the same start, the one read first wins
                Comparator<Integer> byStart = (x, y) -> compare(hi(startHi, x), startLo[x], hi(startHi, y), startLo[y]);
                Arrays.sort(boxed, byStart);
                order = new int[size];
                for (int i = 0; i < size; i++) order[i] = boxed[i];
            }
            Out out = new Out(size + 16, v6);
            boolean first = true;
            boolean hasNext = true; // false once a range reached the very last address
            long nextHi = 0;
            long nextLo = 0; // the first address after the previous range
            for (int k = 0; k < size; k++) {
                int i = order == null ? k : order[k];
                long sHi = hi(startHi, i);
                long sLo = startLo[i];
                long eHi = hi(endHi, i);
                long eLo = endLo[i];
                if (!first) {
                    if (!hasNext) break; // everything after overlaps a range that ran to the end
                    if (compare(sHi, sLo, nextHi, nextLo) < 0) {
                        if (compare(eHi, eLo, nextHi, nextLo) < 0) continue; // inside the previous range
                        sHi = nextHi; // overlap: starts where the previous range ended
                        sLo = nextLo;
                    } else if (compare(sHi, sLo, nextHi, nextLo) > 0) {
                        out.add(nextHi, nextLo, (short) 0); // gap
                    }
                }
                out.add(sHi, sLo, country[i]);
                first = false;
                if (v6) {
                    if (eLo != -1L) {
                        nextHi = eHi;
                        nextLo = eLo + 1;
                    } else if (eHi != -1L) {
                        nextHi = eHi + 1;
                        nextLo = 0;
                    } else {
                        hasNext = false;
                    }
                } else {
                    hasNext = eLo < 0xFFFF_FFFFL;
                    nextLo = eLo + 1;
                }
            }
            if (!first && hasNext) out.add(nextHi, nextLo, (short) 0); // after the last range
            return new Boundaries(out.hi, out.lo, out.country, out.size);
        }
    }

    /**
     * Growing boundary arrays (IPv4 ones without high halves); a boundary with the same country as the one before is
     * dropped (merged).
     */
    private static final class Out {

        long[] hi;
        long[] lo;
        short[] country;
        int size;
        /** The country before the first boundary: none. */
        private short last;

        Out(int capacity, boolean v6) {
            hi = new long[v6 ? capacity : 0];
            lo = new long[capacity];
            country = new short[capacity];
        }

        void add(long h, long l, short cc) {
            if (cc == last) return;
            if (size == lo.length) {
                int cap = size * 2 + 16;
                if (hi.length > 0) hi = Arrays.copyOf(hi, cap);
                lo = Arrays.copyOf(lo, cap);
                country = Arrays.copyOf(country, cap);
            }
            if (hi.length > 0) hi[size] = h;
            lo[size] = l;
            country[size] = cc;
            size++;
            last = cc;
        }
    }

    /** Unsigned 128-bit comparison. */
    static int compare(long aHi, long aLo, long bHi, long bLo) {
        int c = Long.compareUnsigned(aHi, bHi);
        return c != 0 ? c : Long.compareUnsigned(aLo, bLo);
    }

    // ------------------------------------------------------------------ address literals

    /** "a.b.c.d" (decimal, 0-255 each) as an unsigned value, or -1 when it isn't one. */
    static long parseV4(String s) {
        int len = s.length();
        if (len < 7 || len > 15) return -1;
        long value = 0;
        int part = 0;
        int digits = 0;
        int dots = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                part = part * 10 + (c - '0');
                if (++digits > 3 || part > 255) return -1;
            } else if (c == '.') {
                if (digits == 0 || dots == 3) return -1;
                value = (value << 8) | part;
                dots++;
                part = 0;
                digits = 0;
            } else {
                return -1;
            }
        }
        if (digits == 0 || dots != 3) return -1;
        return (value << 8) | part;
    }

    /**
     * An IPv6 literal as {high, low} 64 bits, or null when it isn't one: eight groups of one to four hex digits, at
     * most one "::" standing for one or more zero groups, and optionally the last 32 bits as a dotted IPv4 address
     * ("::ffff:1.2.3.4"). Zone ids ("%eth0") are refused.
     */
    static long @Nullable [] parseV6(String s) {
        int n = s.length();
        if (n < 2 || n > 45) return null;
        int[] groups = new int[8];
        int count = 0;
        int gap = -1; // index of the group the "::" stands before
        int i = 0;
        if (s.startsWith("::")) {
            gap = 0;
            i = 2;
        } else if (s.charAt(0) == ':') {
            return null;
        }
        while (i < n) {
            int start = i;
            int value = 0;
            int digits = 0;
            while (i < n && hex(s.charAt(i)) >= 0) {
                if (++digits > 4) return null;
                value = (value << 4) | hex(s.charAt(i));
                i++;
            }
            if (i < n && s.charAt(i) == '.') { // the last 32 bits as an IPv4 address
                long v4 = parseV4(s.substring(start));
                if (v4 < 0 || count > 6) return null;
                groups[count++] = (int) (v4 >>> 16);
                groups[count++] = (int) (v4 & 0xFFFF);
                i = n;
                break;
            }
            if (digits == 0 || count == 8) return null;
            groups[count++] = value;
            if (i == n) break;
            if (s.charAt(i) != ':') return null;
            i++;
            if (i < n && s.charAt(i) == ':') {
                if (gap >= 0) return null; // a second "::"
                gap = count;
                i++;
            } else if (i == n) {
                return null; // a single trailing ':'
            }
        }
        if (gap < 0 ? count != 8 : count > 7) return null;
        int[] full = new int[8];
        if (gap < 0) {
            full = groups;
        } else {
            int tail = count - gap;
            System.arraycopy(groups, 0, full, 0, gap);
            System.arraycopy(groups, gap, full, 8 - tail, tail);
        }
        long hi = 0;
        long lo = 0;
        for (int g = 0; g < 4; g++) hi = (hi << 16) | full[g];
        for (int g = 4; g < 8; g++) lo = (lo << 16) | full[g];
        return new long[] {hi, lo};
    }

    /** An ASCII hex digit's value, or -1 (Character.digit would also take other scripts' digits). */
    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static long readLong(byte[] b, int at) {
        return ((long) readInt(b, at) << 32) | (readInt(b, at + 4) & 0xFFFF_FFFFL);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "IpTable[v4=%d, v6=%d]", v4Ranges, v6Ranges);
    }
}
