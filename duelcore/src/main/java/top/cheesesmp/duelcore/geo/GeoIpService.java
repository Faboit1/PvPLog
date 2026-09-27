package top.cheesesmp.duelcore.geo;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Country from the IP address ({@code geo} in config.yml). The free db-ip.com "IP to Country Lite" database (CC BY
 * 4.0, "IP Geolocation by DB-IP") is kept in {@code plugins/DuelCore/geoip/}: on start it is loaded when it is fresh
 * enough ({@code geo.max-age-days}), otherwise this month's copy is downloaded first (the previous month's while
 * this month's isn't out yet). A download that fails, or isn't a country database, keeps the old file. Downloading
 * and parsing run on their own thread, and the finished {@link IpTable} is swapped in at once, so a lookup never
 * waits and never touches the disk. The age is checked again twice a day and on /duelcore reload.
 *
 * <p>When a player joins with {@link Setting#AUTO_COUNTRY} on, their address is looked up in memory and their country
 * (and their region, if they have none yet) is set when it changed. Private, local and unknown addresses change
 * nothing. Players who joined before the table was ready are looked up once it is. Addresses are never stored or
 * logged; verbose mode logs the country found.
 */
public final class GeoIpService implements Listener {

    /** What a lookup did. */
    public enum Result {
        /** The country (or region) changed. */
        CHANGED,
        /** Found the country the player already had. */
        SAME,
        /** The address has no known country (private, local, or not in the database). */
        UNKNOWN,
        /** The database is still loading (the player is looked up once it is ready), or the profile isn't loaded. */
        NOT_READY,
        /** {@code geo.enabled} is off. */
        DISABLED
    }

    /** The database file in {@code plugins/DuelCore/geoip/}. */
    public static final String FILE = "dbip-country-lite.csv.gz";
    /** The bundled {@code geo.database-url}. */
    public static final String DEFAULT_URL = "https://download.db-ip.com/free/dbip-country-lite-{year}-{month}.csv.gz";
    /** A download larger than this is refused (the real file is about 5 MB). */
    private static final long MAX_DOWNLOAD = 64L << 20;
    /** A download that takes longer than this is given up. */
    private static final Duration MAX_DOWNLOAD_TIME = Duration.ofMinutes(10);
    /** A file with fewer IPv4 ranges than this is not a country database (the real one has about 357 000). */
    static final int MIN_V4_RANGES = 10_000;
    /** Ticks between two age checks (12 hours). */
    private static final long CHECK_PERIOD = 20L * 60 * 60 * 12;

    private final DuelCorePlugin plugin;
    private final Path file;
    private final ExecutorService worker;
    /** Online players who joined (or were online at start) before the table was ready. */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile IpTable table = IpTable.EMPTY;
    private volatile String state = "off";
    private volatile @Nullable Instant fileTime;
    private @Nullable BukkitTask checker;

    public GeoIpService(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("geoip").resolve(FILE);
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DuelCore-GeoIP");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    /** Loads (or first downloads) the database in the background; players already online are looked up once it's in. */
    public void enable() {
        checker = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, CHECK_PERIOD, CHECK_PERIOD);
        refresh();
    }

    /** /duelcore reload: {@code geo.enabled} may have changed, and the file may be due. */
    public void reload() {
        refresh();
    }

    public void disable() {
        if (checker != null) checker.cancel();
        worker.shutdownNow();
        pending.clear();
    }

    /**
     * Drops the table when {@code geo.enabled} is off; otherwise loads the file in the background when the table is
     * empty, and downloads a new one first when the file is missing or older than {@code geo.max-age-days}. Main
     * thread; at most one refresh runs at a time.
     */
    public void refresh() {
        MainConfig cfg = plugin.settings();
        if (!cfg.geoEnabled) {
            table = IpTable.EMPTY;
            state = "off";
            pending.clear();
            return;
        }
        if (table.isEmpty()) for (Player p : Bukkit.getOnlinePlayers()) pending.add(p.getUniqueId());
        if (!busy.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    IpTable loaded = update(cfg.geoDatabaseUrl, cfg.geoMaxAgeDays);
                    if (loaded != null && !worker.isShutdown()) swap(loaded, cfg.verbose);
                } catch (Throwable t) {
                    state = "failed: " + t.getMessage();
                    // (stopped by the plugin shutting down: nothing to report)
                    if (!worker.isShutdown()) plugin.getLogger().log(Level.WARNING, "Loading the GeoIP database failed", t);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            busy.set(false); // disabled meanwhile
        }
    }

    /**
     * On the worker thread: downloads a new file when it is missing or too old (keeping the old one when that fails)
     * and returns the table to use from now on, or null to keep the current one.
     */
    private @Nullable IpTable update(String url, int maxAgeDays) throws IOException {
        Instant modified = Files.exists(file) ? Files.getLastModifiedTime(file).toInstant() : null;
        boolean stale = modified == null || modified.isBefore(Instant.now().minus(Duration.ofDays(maxAgeDays)));
        if (stale) {
            state = "downloading";
            try {
                IpTable downloaded = download(url, YearMonth.now(ZoneOffset.UTC), file,
                    "DuelCore/" + plugin.getPluginMeta().getVersion() + " (Minecraft server plugin)", MAX_DOWNLOAD);
                fileTime = Instant.now();
                plugin.getLogger().info("Downloaded the GeoIP database (" + downloaded.v4Ranges() + " IPv4 and "
                    + downloaded.v6Ranges() + " IPv6 ranges). IP Geolocation by DB-IP (https://db-ip.com), CC BY 4.0.");
                return downloaded;
            } catch (IOException e) {
                plugin.getLogger().warning("Could not download the GeoIP database (" + e.getMessage() + ")"
                    + (modified != null ? "; using the one from " + modified.toString().substring(0, 10) : "") + ".");
                if (modified == null) {
                    state = "no database (download failed: " + e.getMessage() + ")";
                    return null;
                }
            }
        }
        if (!table.isEmpty()) {
            state = "loaded";
            return null; // the file in use is still fresh enough, or no newer one could be had
        }
        state = "loading";
        IpTable read = read(file);
        if (read.v4Ranges() < MIN_V4_RANGES) {
            state = "no database (" + FILE + " has only " + read.v4Ranges() + " ranges)";
            plugin.getLogger().warning("geoip/" + FILE + " is not a country database; delete it to download a new one.");
            return null;
        }
        fileTime = modified;
        return read;
    }

    /** Puts a new table in use and looks up everyone who waited for it (on the main thread). */
    private void swap(IpTable loaded, boolean verbose) {
        if (!plugin.settings().geoEnabled) return; // switched off (reload) while it loaded
        table = loaded;
        state = "loaded";
        if (verbose) plugin.getLogger().info("GeoIP database loaded: " + loaded);
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID id : List.copyOf(pending)) {
                    pending.remove(id);
                    Player p = Bukkit.getPlayer(id);
                    PlayerProfile profile = p == null ? null : plugin.profiles().get(p);
                    if (profile != null && profile.setting(Setting.AUTO_COUNTRY)) detect(p);
                }
            });
        } catch (RuntimeException e) {
            // the plugin was disabled meanwhile
        }
    }

    // ------------------------------------------------------------------ download

    /**
     * Downloads the database of {@code now} from {@code template} (the month before when {@code now}'s isn't
     * published yet, HTTP 404) next to {@code file}, checks that it reads as a country database and only then puts it
     * in place of {@code file}; returns the table read from it. Anything else (an error status, a file that isn't a
     * database, one larger than {@code maxBytes}) throws and leaves {@code file} as it was. Worker thread.
     */
    static IpTable download(String template, YearMonth now, Path file, String userAgent, long maxBytes) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path part = folder.resolve(file.getFileName() + ".part");
        boolean monthly = template.contains("{year}") || template.contains("{month}");
        String lastUrl = null;
        try {
            for (int back = 0; back < (monthly ? 2 : 1); back++) {
                String url = url(template, now.minusMonths(back));
                lastUrl = url;
                int status = fetch(url, part, userAgent, maxBytes);
                if (status == HttpURLConnection.HTTP_NOT_FOUND) continue; // not out yet: the month before
                if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status + " from " + url);
                IpTable t = read(part);
                if (t.v4Ranges() < MIN_V4_RANGES) throw new IOException(url + " is not a country database");
                try {
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return t;
            }
            throw new IOException("HTTP 404 from " + lastUrl);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** {@code template} with {@code {year}} and {@code {month}} (two digits) filled in. */
    public static String url(String template, YearMonth month) {
        return template.replace("{year}", String.valueOf(month.getYear()))
            .replace("{month}", String.format(Locale.ROOT, "%02d", month.getMonthValue()));
    }

    /**
     * GETs {@code url} (http or https only) into {@code to}; returns the HTTP status, the body is only written for
     * 200. Connecting may take 15 s, the server may go quiet for 30 s at a time, and the whole download may take
     * {@link #MAX_DOWNLOAD_TIME}; a body larger than {@code maxBytes} throws.
     */
    static int fetch(String url, Path to, String userAgent, long maxBytes) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("bad geo.database-url " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) throw new IOException("geo.database-url must be an http(s) URL");
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent);
        try {
            int status = c.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return status;
            if (c.getContentLengthLong() > maxBytes) throw new IOException("the file is larger than " + maxBytes + " bytes");
            long deadline = System.nanoTime() + MAX_DOWNLOAD_TIME.toNanos();
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(to)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IOException("the file is larger than " + maxBytes + " bytes");
                    if (System.nanoTime() > deadline) throw new IOException("the download took too long");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("stopped");
                    out.write(buf, 0, n);
                }
            }
            return status;
        } finally {
            c.disconnect();
        }
    }

    /** Reads a database file, gzipped (as downloaded) or plain CSV. */
    static IpTable read(Path path) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(path), 65536)) {
            raw.mark(2);
            boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b;
            raw.reset();
            InputStream in = gzip ? new GZIPInputStream(raw, 65536) : raw;
            return IpTable.read(new InputStreamReader(in, StandardCharsets.US_ASCII));
        }
    }

    // ------------------------------------------------------------------ detection

    /** After the hub has set the player up (HubListener, HIGH). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile != null && profile.setting(Setting.AUTO_COUNTRY)) detect(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Looks the player up and, when their country (or missing region) changed, saves it and refreshes their tags and
     * sidebar. Main thread.
     */
    public Result detect(Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) return Result.NOT_READY;
        Result r = apply(player, profile);
        if (r == Result.CHANGED) {
            plugin.profiles().saveSettings(profile);
            plugin.tags().update(player);
            plugin.sidebar().refresh(player);
        }
        return r;
    }

    /**
     * Looks the player's address up and puts the country (and a region, if they have none) on their profile, without
     * saving: for callers that save the profile themselves. Main thread.
     */
    public Result apply(Player player, PlayerProfile profile) {
        if (!plugin.settings().geoEnabled) return Result.DISABLED;
        IpTable t = table;
        if (t.isEmpty()) {
            pending.add(player.getUniqueId());
            return Result.NOT_READY;
        }
        InetSocketAddress socket = player.getAddress();
        InetAddress address = socket == null ? null : socket.getAddress();
        String found = address == null || !isPublic(address) ? null : t.lookup(address);
        String cc = found == null ? null : Countries.code(found);
        if (cc == null || !plugin.flags().isKnown(cc)) return Result.UNKNOWN;
        boolean changed = false;
        if (!cc.equals(profile.country())) {
            profile.country(cc);
            changed = true;
        }
        String region = plugin.flags().region(cc);
        if (profile.region() == null && region != null && plugin.settings().regions.contains(region)) {
            profile.region(region);
            changed = true;
        }
        if (changed && plugin.settings().verbose) plugin.getLogger().info("GeoIP: " + player.getName() + " is in country " + cc);
        return changed ? Result.CHANGED : Result.SAME;
    }

    /**
     * False for addresses that can't tell a country: unspecified, loopback, link-local, private (10/8, 172.16/12,
     * 192.168/16, fc00::/7, fec0::/10), carrier-grade NAT (100.64/10) and multicast.
     */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) return b[0] != 0 && !(b[0] == 100 && (b[1] & 0xC0) == 64);
        return (b[0] & 0xFE) != 0xFC;
    }

    // ------------------------------------------------------------------ status

    /** The table in use (empty until loaded). */
    public IpTable table() {
        return table;
    }

    /** One line for /duelcore debug. */
    public String describe() {
        Instant t = fileTime;
        IpTable current = table;
        String age = t == null ? "" : " file " + t.toString().substring(0, 10) + " ("
            + Duration.between(t, Instant.now()).toDays() + "d old)";
        return state + (current.isEmpty() ? "" : " v4=" + current.v4Ranges() + " v6=" + current.v6Ranges()) + age
            + " waiting=" + pending.size();
    }
}
