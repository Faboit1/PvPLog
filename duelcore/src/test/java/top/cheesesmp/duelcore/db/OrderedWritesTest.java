package top.cheesesmp.duelcore.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Work on a multi-threaded pool (like MySQL) must still run, and complete, in the order it was submitted. */
class OrderedWritesTest {

    private static Function<SqlWork<Void>, CompletableFuture<Void>> pool(ExecutorService executor, AtomicInteger calls,
                                                                         int failEvery) {
        return work -> {
            int n = calls.incrementAndGet();
            if (failEvery > 0 && n % failEvery == 0) {
                // e.g. no connection: the work never runs, sometimes failing right away, sometimes later
                return n % 2 == 0 ? CompletableFuture.failedFuture(new SQLException("no connection"))
                    : CompletableFuture.supplyAsync(() -> {
                        throw new CompletionException(new SQLException("no connection"));
                    }, executor);
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return work.run(null);
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, executor);
        };
    }

    private static List<Integer> run(int failEvery) {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            OrderedWrites writes = new OrderedWrites(pool(executor, new AtomicInteger(), failEvery));
            Random random = new Random(7);
            List<CompletableFuture<Void>> all = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                int n = i;
                long pause = random.nextInt(300_000);
                all.add(writes.submit(c -> {
                    LockSupport.parkNanos(pause); // uneven work, so an unordered pool would shuffle
                    order.add(n);
                    return null;
                }));
            }
            for (CompletableFuture<Void> f : all) f.handle((ok, e) -> null).join();
            return List.copyOf(order);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void writesRunInOrderOnAPool() {
        List<Integer> order = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> run(0));
        assertEquals(IntStream.range(0, 300).boxed().toList(), order);
    }

    @Test
    void callbacksRunInSubmissionOrderWithResults() {
        List<Integer> completed = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            ExecutorService executor = Executors.newFixedThreadPool(6);
            try {
                List<Integer> order = Collections.synchronizedList(new ArrayList<>());
                OrderedWrites writes = new OrderedWrites(pool(executor, new AtomicInteger(), 7));
                Random random = new Random(11);
                List<CompletableFuture<Integer>> all = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    int n = i;
                    long pause = random.nextInt(200_000);
                    all.add(writes.submit(c -> {
                        LockSupport.parkNanos(pause);
                        return n * 2;
                    }, (value, error) -> {
                        if (error == null) assertEquals(n * 2, value);
                        order.add(n); // failed work gets its callback too, in its turn
                    }));
                }
                for (CompletableFuture<Integer> f : all) f.handle((ok, e) -> null).join();
                return List.copyOf(order);
            } finally {
                executor.shutdownNow();
            }
        });
        assertEquals(IntStream.range(0, 200).boxed().toList(), completed);
    }

    @Test
    void failedWritesDontBlockTheOnesAfter() {
        List<Integer> order = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> run(10));
        List<Integer> sorted = new ArrayList<>(order);
        Collections.sort(sorted);
        assertEquals(sorted, order);
        assertEquals(270, order.size()); // every 10th never ran
    }
}
