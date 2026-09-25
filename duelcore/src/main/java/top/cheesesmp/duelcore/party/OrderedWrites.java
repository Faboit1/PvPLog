package top.cheesesmp.duelcore.party;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import top.cheesesmp.duelcore.db.SqlWork;

/**
 * Runs party writes on the database threads in exactly the order they were made (a leave followed by a join must
 * never be swapped). SQLite has one database thread, so that is free; the MySQL pool has several, so each write
 * waits until the ones before it are done. Writes are handed to the database executor right away, so a shutdown
 * still drains them. A write that fails, even before it ran (no connection), counts as done, and nothing waits
 * longer than {@link #MAX_WAIT_MS}.
 */
final class OrderedWrites {

    private static final long MAX_WAIT_MS = 30_000;

    /** Runs one unit of work on a database thread, e.g. {@code Database::transaction}. */
    private final Function<SqlWork<Void>, CompletableFuture<Void>> runner;
    private final Object lock = new Object();
    /** Writes handed out (main thread only). */
    private long issued;
    /** Every write up to this one is done (guarded by {@link #lock}). */
    private long finished;
    /** Done writes after a gap (guarded by {@link #lock}). */
    private final Set<Long> done = new HashSet<>();

    OrderedWrites(Function<SqlWork<Void>, CompletableFuture<Void>> runner) {
        this.runner = runner;
    }

    CompletableFuture<Void> submit(SqlWork<?> work) {
        long seq = ++issued;
        CompletableFuture<Void> future = runner.apply(c -> {
            try {
                awaitTurn(seq);
                work.run(c);
            } finally {
                markDone(seq);
            }
            return null;
        });
        future.whenComplete((ok, error) -> {
            if (error != null) markDone(seq); // also when the work never started
        });
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
            // the writes before this one were queued first, so they are already running on other threads
            while (finished < seq - 1) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted while waiting for an earlier party write", e);
                }
            }
        }
    }
}
