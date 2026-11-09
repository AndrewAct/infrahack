package io.infrahack.explorejavaconcurrency.part03;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LunchRushTest {
    @Test
    void iteratorKeepsItsOriginalRouteSnapshot() {
        CopyOnWriteArrayList<String> routes = new CopyOnWriteArrayList<>(List.of("shelf", "warmer"));
        Iterator<String> snapshot = routes.iterator();
        routes.add("freezer");
        List<String> seen = new ArrayList<>();
        snapshot.forEachRemaining(seen::add);
        assertEquals(List.of("shelf", "warmer"), seen);
        assertEquals(List.of("shelf", "warmer", "freezer"), routes);
    }

    @Test
    @Timeout(15)
    void platformPoolAdmitsBoundedWorkAndPreservesCounts() throws Exception {
        assertBounds(LunchRushExperiment.run(LunchRushExperiment.Mode.PLATFORM, 4, 60, 7));
    }

    @Test
    @Timeout(15)
    void virtualThreadsAlsoNeedAnExplicitAdmissionBound() throws Exception {
        assertBounds(LunchRushExperiment.run(LunchRushExperiment.Mode.VIRTUAL, 0, 60, 7));
    }

    private static void assertBounds(LunchRushExperiment.Result result) {
        assertEquals(result.submitted(), result.accepted() + result.rejected());
        assertEquals(result.accepted(), result.fresh() + result.expired() + result.interrupted());
        assertTrue(result.maxInFlight() <= 20);
        assertTrue(result.maxHandoffs() <= 5);
        assertEquals(0, result.interrupted());
    }
}
