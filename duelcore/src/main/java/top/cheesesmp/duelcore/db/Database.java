package top.cheesesmp.duelcore.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All database access goes through here. Work runs on dedicated "DuelCore-DB" threads
 * (a single writer thread for SQLite, a small pool for MySQL), never on the server thread.
 * {@link #mainThreadExecutions()} counts any SQL that ran on the main thread (should stay 0).
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final Dialect dialect;
    private final Logger logger;
    private final BooleanSupplier onMainThread;

    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong mainThreadExecutions = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong totalNanos = new AtomicLong();

    private Database(HikariDataSource dataSource, int threads, Dialect dialect, Logger logger, BooleanSupplier onMainThread) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.logger = logger;
        this.onMainThread = onMainThread;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "DuelCore-DB-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = threads == 1 ? Executors.newSingleThreadExecutor(factory) : Executors.newFixedThreadPool(threads, factory);
    }

    public static Database sqlite(File file, Logger logger, BooleanSupplier onMainThread) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("DuelCore-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setMaxLifetime(0);
        config.setIdleTimeout(0);
        config.setConnectionTimeout(10_000);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("busy_timeout", "5000");
        return new Database(new HikariDataSource(config), 1, Dialect.SQLITE, logger, onMainThread);
    }

    public static Database mysql(String host, int port, String database, String user, String password, int poolSize,
                                 Map<String, String> properties, Logger logger, BooleanSupplier onMainThread) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("DuelCore-MySQL");
        config.setDriverClassName(driverAvailable("com.mysql.cj.jdbc.Driver") ? "com.mysql.cj.jdbc.Driver" : "org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, poolSize));
        config.setMinimumIdle(Math.min(2, poolSize));
        config.setConnectionTimeout(10_000);
        config.setMaxLifetime(1_500_000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("characterEncoding", "utf8");
        properties.forEach(config::addDataSourceProperty);
        return new Database(new HikariDataSource(config), Math.max(2, poolSize), Dialect.MYSQL, logger, onMainThread);
    }

    private static boolean driverAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Runs work with a connection in auto-commit mode. */
    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        return run(work, false);
    }

    /** Runs work in a single transaction, rolled back on failure. */
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return run(work, true);
    }

    private <T> CompletableFuture<T> run(SqlWork<T> work, boolean transactional) {
        pending.incrementAndGet();
        try {
            return CompletableFuture.supplyAsync(() -> execute(work, transactional), executor);
        } catch (RuntimeException rejected) {
            pending.decrementAndGet();
            return CompletableFuture.failedFuture(rejected);
        }
    }

    private <T> T execute(SqlWork<T> work, boolean transactional) {
        long start = System.nanoTime();
        if (onMainThread.getAsBoolean()) {
            mainThreadExecutions.incrementAndGet();
            logger.log(Level.WARNING, "DuelCore: database work ran on the main thread", new IllegalStateException());
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!transactional) return work.run(connection);
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            failures.incrementAndGet();
            logger.log(Level.SEVERE, "DuelCore: database error: " + e.getMessage(), e);
            throw new CompletionException(e);
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            logger.log(Level.SEVERE, "DuelCore: database task failed", e);
            throw e;
        } finally {
            executed.incrementAndGet();
            totalNanos.addAndGet(System.nanoTime() - start);
            pending.decrementAndGet();
        }
    }

    public long executed() {
        return executed.get();
    }

    public long mainThreadExecutions() {
        return mainThreadExecutions.get();
    }

    public long failures() {
        return failures.get();
    }

    public int pending() {
        return pending.get();
    }

    public double averageMillis() {
        long n = executed.get();
        return n == 0 ? 0 : totalNanos.get() / 1_000_000.0 / n;
    }

    /** Waits (up to 20 s) for queued writes, then closes the pool. Called from onDisable. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                logger.warning("DuelCore: database queue did not drain in time; " + pending.get() + " task(s) dropped");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
