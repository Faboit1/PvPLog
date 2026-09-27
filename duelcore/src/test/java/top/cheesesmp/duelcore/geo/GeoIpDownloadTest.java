package top.cheesesmp.duelcore.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The database download against a local web server: last month's file while this month's isn't out, and a failed or
 * wrong download never replaces the file there is.
 */
class GeoIpDownloadTest {

    private static final YearMonth NOW = YearMonth.of(2026, 9);
    private static final long MAX = 64L << 20;
    private static final String UA = "DuelCore/test";

    @TempDir
    Path dir;

    private HttpServer server;
    /** Path → body; missing = 404, "500" = an error. */
    private final Map<String, byte[]> bodies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path + " " + exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = bodies.get(path);
            int status = body == null ? 404 : new String(body, StandardCharsets.US_ASCII).equals("500") ? 500 : 200;
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String template() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/db-{year}-{month}.csv.gz";
    }

    /** A gzipped CSV with {@code ranges} IPv4 ranges of {@code country} and another country taking turns. */
    private static byte[] database(int ranges, String country) throws IOException {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < ranges; i++) {
            long start = (1L << 24) + i * 256L;
            csv.append(v4(start)).append(',').append(v4(start + 255)).append(',').append(i % 2 == 0 ? country : "XX").append('\n');
        }
        csv.append("2a00::,2a00:ffff:ffff:ffff:ffff:ffff:ffff:ffff,").append(country).append('\n');
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(csv.toString().getBytes(StandardCharsets.US_ASCII));
        }
        return bytes.toByteArray();
    }

    private static String v4(long a) {
        return (a >>> 24) + "." + ((a >>> 16) & 255) + "." + ((a >>> 8) & 255) + "." + (a & 255);
    }

    private Path file() {
        return dir.resolve("geoip").resolve(GeoIpService.FILE);
    }

    @Test
    void takesLastMonthWhileThisMonthIsNotOut() throws IOException {
        bodies.put("/db-2026-08.csv.gz", database(GeoIpService.MIN_V4_RANGES + 1, "DE"));
        IpTable t = GeoIpService.download(template(), NOW, file(), UA, MAX);
        assertEquals("DE", t.lookup("1.0.0.1"));
        assertEquals("DE", t.lookup("2a00::1"));
        assertEquals("DE", GeoIpService.read(file()).lookup("1.0.0.1"), "saved");
        assertEquals(List.of("/db-2026-09.csv.gz " + UA, "/db-2026-08.csv.gz " + UA), requests);
        assertFalse(Files.exists(file().resolveSibling(GeoIpService.FILE + ".part")));
    }

    @Test
    void replacesAnOldFile() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "old");
        bodies.put("/db-2026-09.csv.gz", database(GeoIpService.MIN_V4_RANGES + 1, "FR"));
        assertEquals("FR", GeoIpService.download(template(), NOW, file(), UA, MAX).lookup("1.0.0.1"));
        assertEquals(1, requests.size(), "this month's file is out");
        assertEquals("FR", GeoIpService.read(file()).lookup("1.0.0.1"));
    }

    @Test
    void aWrongOrFailedDownloadKeepsTheOldFile() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "old");
        bodies.put("/db-2026-09.csv.gz", "<html>not a database</html>".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> GeoIpService.download(template(), NOW, file(), UA, MAX));
        assertEquals("old", Files.readString(file()));

        bodies.put("/db-2026-09.csv.gz", database(50, "DE"));
        IOException tooSmall = assertThrows(IOException.class, () -> GeoIpService.download(template(), NOW, file(), UA, MAX));
        assertTrue(tooSmall.getMessage().contains("not a country database"), tooSmall.getMessage());

        bodies.put("/db-2026-09.csv.gz", "500".getBytes(StandardCharsets.US_ASCII));
        requests.clear();
        IOException error = assertThrows(IOException.class, () -> GeoIpService.download(template(), NOW, file(), UA, MAX));
        assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
        assertEquals(1, requests.size(), "only a missing file falls back to last month");

        bodies.put("/db-2026-09.csv.gz", database(GeoIpService.MIN_V4_RANGES + 1, "DE"));
        IOException big = assertThrows(IOException.class, () -> GeoIpService.download(template(), NOW, file(), UA, 1000));
        assertTrue(big.getMessage().contains("larger"), big.getMessage());

        assertEquals("old", Files.readString(file()));
        assertFalse(Files.exists(file().resolveSibling(GeoIpService.FILE + ".part")), "no half files left");
    }

    @Test
    void neitherMonthOut() {
        IOException e = assertThrows(IOException.class, () -> GeoIpService.download(template(), NOW, file(), UA, MAX));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertEquals(2, requests.size());
        assertFalse(Files.exists(file()));
    }

    @Test
    void aFixedUrlIsTriedOnce() {
        String fixed = "http://127.0.0.1:" + server.getAddress().getPort() + "/fixed.csv.gz";
        assertThrows(IOException.class, () -> GeoIpService.download(fixed, NOW, file(), UA, MAX));
        assertEquals(1, requests.size());
    }

    @Test
    void onlyWebAddresses() {
        for (String url : List.of("file:///etc/passwd", "ftp://example.org/db.csv.gz", "jar:file:/x.jar!/a", "not a url at all")) {
            assertThrows(IOException.class, () -> GeoIpService.fetch(url, dir.resolve("x"), UA, MAX), url);
        }
        assertFalse(Files.exists(dir.resolve("x")));
    }
}
