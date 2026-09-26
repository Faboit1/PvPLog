package top.cheesesmp.duelcore.arena;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs {@link RegionJob}s on the main thread within a time budget per tick, so pasting and resetting many arenas never
 * causes a lag spike. Jobs run by priority, then in the order they were submitted: the oldest job of the highest
 * priority gets the whole budget, and later jobs only get time it can't use (while it waits for chunks or its diff).
 * One job at a time finishes quickly; sharing the budget round-robin made every job finish late.
 * While an {@link #URGENT} job is queued (a match is waiting for it) the budget grows up to the urgent budget, as far
 * as the server's tick time leaves room.
 */
public final class BlockJobQueue implements Runnable {

    /** The reset before a match's next round: small, and both players are waiting mid-match. */
    public static final int ROUND = 3;
    /** A match is waiting for this job (its arena). */
    public static final int URGENT = 2;
    /** An arena being reset after its match. */
    public static final int NORMAL = 1;
    /** Background work: pre-warming, clearing old slots, destroying instances. */
    public static final int LOW = 0;

    /** Tick time (ms) the urgent budget stays under, other work included. */
    private static final double TARGET_TICK_MS = 45;

    /** Highest priority first, then oldest first. */
    private static final Comparator<RegionJob> BY_PRIORITY = (a, b) -> a.priority() != b.priority()
        ? Integer.compare(b.priority(), a.priority()) : Long.compare(a.seq, b.seq);

    private final List<RegionJob> jobs = new ArrayList<>();
    private final Logger logger;
    private volatile double budgetMs;
    private volatile double urgentBudgetMs;
    private long seq;
    private long completed;
    private long nanosUsed;
    private long ticksRun;
    /** Our own recent main-thread time per tick (moving average, ms), to tell it apart from the rest of the tick. */
    private double ownMs;

    public BlockJobQueue(Logger logger, double budgetMs) {
        this(logger, budgetMs, budgetMs);
    }

    public BlockJobQueue(Logger logger, double budgetMs, double urgentBudgetMs) {
        this.logger = logger;
        this.budgetMs = budgetMs;
        this.urgentBudgetMs = urgentBudgetMs;
    }

    public void budget(double ms) {
        this.budgetMs = ms;
    }

    public void budget(double ms, double urgentMs) {
        this.budgetMs = ms;
        this.urgentBudgetMs = Math.max(ms, urgentMs);
    }

    public RegionJob submit(RegionJob job) {
        job.seq = seq++;
        job.begin();
        jobs.add(job);
        return job;
    }

    public int size() {
        return jobs.size();
    }

    public long completed() {
        return completed;
    }

    public double averageMsPerTick() {
        return nanosUsed / 1_000_000.0 / Math.max(1, ticksRun);
    }

    /** This tick's budget: the normal one, or while a match waits, up to the urgent one within the tick target. */
    double budgetFor(boolean urgent, double avgTickMs) {
        if (!urgent || urgentBudgetMs <= budgetMs) return budgetMs;
        double room = TARGET_TICK_MS - (avgTickMs - ownMs);
        return Math.max(budgetMs, Math.min(urgentBudgetMs, room));
    }

    @Override
    public void run() {
        if (jobs.isEmpty()) {
            ownMs *= 0.9;
            return;
        }
        long start = System.nanoTime();
        jobs.sort(BY_PRIORITY);
        boolean urgent = jobs.getFirst().priority() >= URGENT;
        double avgTick;
        try {
            avgTick = org.bukkit.Bukkit.getAverageTickTime();
        } catch (Throwable t) {
            avgTick = 0;
        }
        long deadline = start + (long) (budgetFor(urgent, avgTick) * 1_000_000);
        int rounds = 0;
        while (!jobs.isEmpty() && System.nanoTime() < deadline && rounds++ < jobs.size() * 4 + 4) {
            boolean progressed = false;
            for (Iterator<RegionJob> it = jobs.iterator(); it.hasNext(); ) {
                RegionJob job = it.next();
                RegionJob.Phase before = job.phase();
                boolean done;
                try {
                    // the oldest job of the highest priority gets all that's left; it returns early only while it
                    // waits for chunks or its diff, and then the next job gets the time
                    done = job.step(Math.max(deadline, System.nanoTime() + 200_000));
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
        long used = System.nanoTime() - start;
        nanosUsed += used;
        ticksRun++;
        ownMs = ownMs * 0.9 + used / 1_000_000.0 * 0.1;
    }

    /** Fails every pending job (plugin disable). */
    public void cancelAll() {
        for (RegionJob job : jobs) job.future().cancel(false);
        jobs.clear();
    }
}
