package top.cheesesmp.duelcore.db;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Runs database work on the database threads in exactly the order it was submitted (a leave followed by a join, an
 * unfollow followed by a follow, must never be swapped). SQLite has one database thread, so that is free; the MySQL
 * pool has several, so each unit waits until the ones before it are done. The returned futures also complete in
 * submission order, and so do the callbacks given to {@link #submit(SqlWork, BiConsumer)}. Work is handed to the
 * database executor right away, so a shutdown still drains it. Work that fails, even before it ran (no connection),
 * counts as done, and nothing waits longer than {@link #MAX_WAIT_MS}. {@link #submit} is main thread only.
 */
public final class OrderedWrites {

    private static final long MAX_WAIT_MS = 30_000;

    /** Runs one unit of work on a database thread, e.g. {@code Database::transaction}. */
    private final Function<SqlWork<Void>, CompletableFuture<Void>> runner;
    private final Object lock = new Object();
    /** Work handed out (main thread only). */
    private long issued;
    /** The future returned last (main thread only). */
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    /** Every unit up to this one is done (guarded by {@link #lock}). */
    private long finished;
    /** Done units after a gap (guarded by {@link #lock}). */
    private final Set<Long> done = new HashSet<>();

    public OrderedWrites(Function<SqlWork<Void>, CompletableFuture<Void>> runner) {
        this.runner = runner;
    }

    /** Queues {@code work}; the future completes (with its result) after every earlier one has completed. */
    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        return submit(work, (value, error) -> { });
    }

    /**
     * Queues {@code work} and runs {@code callback} (on a database thread, or right away when already done) once it is done, strictly after the
     * callbacks of all earlier work. Use it to hop back to the main thread in order.
     */
    public <T> CompletableFuture<T> submit(SqlWork<T> work, BiConsumer<? super T, ? super Throwable> callback) {
        long seq = ++issued;
        AtomicReference<T> result = new AtomicReference<>();
        CompletableFuture<Void> ran = runner.apply(c -> {
            try {
                awaitTurn(seq);
                result.set(work.run(c));
            } finally {
                markDone(seq);
            }
            return null;
        });
        ran.whenComplete((ok, error) -> {
            if (error != null) markDone(seq); // also when the work never started
        });
        CompletableFuture<T> future = tail.handle((ok, error) -> null).thenCompose(v -> ran).thenApply(v -> result.get())
            .whenComplete(callback);
        tail = future;
        return future;
    }

    private void markDone(long seq) {
        synchronized (lock) {
            if (seq <= finished) return;
            done.add(seq);
            while (done.remove(finished + 1)) finished++;
            lock.notifyAll();
        }
    }

    private void awaitTurn(long seq) throws SQLException {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        synchronized (lock) {
            // the units before this one were queued first, so they are already running on other threads
            while (finished < seq - 1) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted while waiting for earlier database work", e);
                }
            }
        }
    }
}
