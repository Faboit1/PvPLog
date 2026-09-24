package top.cheesesmp.duelcore.arena;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs {@link RegionJob}s on the main thread within a fixed time budget per tick, so pasting and resetting many
 * arenas never causes a lag spike. Jobs are advanced round-robin so one big reset can't starve the others.
 */
public final class BlockJobQueue implements Runnable {

    private final Deque<RegionJob> jobs = new ArrayDeque<>();
    private final Logger logger;
    private volatile double budgetMs;
    private long completed;
    private long nanosUsed;

    public BlockJobQueue(Logger logger, double budgetMs) {
        this.logger = logger;
        this.budgetMs = budgetMs;
    }

    public void budget(double ms) {
        this.budgetMs = ms;
    }

    public RegionJob submit(RegionJob job) {
        job.begin();
        jobs.addLast(job);
        return job;
    }

    public int size() {
        return jobs.size();
    }

    public long completed() {
        return completed;
    }

    public double averageMsPerTick() {
        return nanosUsed / 1_000_000.0 / Math.max(1, completed);
    }

    @Override
    public void run() {
        if (jobs.isEmpty()) return;
        long start = System.nanoTime();
        long deadline = start + (long) (budgetMs * 1_000_000);
        int rounds = 0;
        while (!jobs.isEmpty() && System.nanoTime() < deadline && rounds++ < jobs.size() * 4 + 4) {
            Iterator<RegionJob> it = jobs.iterator();
            boolean progressed = false;
            while (it.hasNext()) {
                RegionJob job = it.next();
                RegionJob.Phase before = job.phase();
                boolean done;
                try {
                    long share = Math.max(200_000, (deadline - System.nanoTime()) / Math.max(1, jobs.size()));
                    done = job.step(System.nanoTime() + share);
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, "Arena block job failed", t);
                    job.future().completeExceptionally(t);
                    done = true;
                }
                if (done) {
                    it.remove();
                    completed++;
                    progressed = true;
                } else if (job.phase() != before) {
                    progressed = true;
                }
                if (System.nanoTime() >= deadline) break;
            }
            if (!progressed) break;
        }
        nanosUsed += System.nanoTime() - start;
    }

    /** Fails every pending job (plugin disable). */
    public void cancelAll() {
        for (RegionJob job : jobs) job.future().cancel(false);
        jobs.clear();
    }
}
