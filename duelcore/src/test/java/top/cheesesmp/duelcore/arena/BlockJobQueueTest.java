package top.cheesesmp.duelcore.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class BlockJobQueueTest {

    private final BlockJobQueue queue = new BlockJobQueue(Logger.getAnonymousLogger(), 4, 15);

    @Test
    void normalBudgetWithoutUrgentJobs() {
        assertEquals(4, queue.budgetFor(false, 10), 1e-9);
    }

    @Test
    void urgentBudgetWhenTheServerHasRoom() {
        assertEquals(15, queue.budgetFor(true, 10), 1e-9);
        assertEquals(10, queue.budgetFor(true, 35), 1e-9); // 45 ms target - 35 ms of other work
    }

    @Test
    void neverBelowTheNormalBudget() {
        assertEquals(4, queue.budgetFor(true, 49), 1e-9);
    }
}
