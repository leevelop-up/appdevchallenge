package com.codrive.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.codrive.vehicle.model.ProposalTracker;
import com.codrive.vehicle.model.ProposalTracker.Proposal;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ProposalTrackerTest {
    private static Proposal proposal(String id, long ttlMillis) throws Exception {
        return new Proposal(id, "민수", "NAV_DESTINATION",
                new JSONObject().put("name", "서울역").put("lat", 37.55).put("lng", 126.97), ttlMillis);
    }

    @Test
    public void proposalIsHeldUntilDriverDecides() throws Exception {
        ProposalTracker t = new ProposalTracker();
        t.add(proposal("r1", 60_000));
        assertEquals(1, t.snapshot().size());
        assertEquals("서울역", t.snapshot().get(0).placeName);
        Proposal taken = t.take("r1");
        assertNotNull(taken);
        assertNull("이미 처리한 제안은 두 번 적용되지 않는다", t.take("r1"));
        assertTrue(t.snapshot().isEmpty());
    }

    @Test
    public void expiredProposalsAreRemovedAndReported() throws Exception {
        ProposalTracker t = new ProposalTracker();
        t.add(proposal("old", 0));
        t.add(proposal("fresh", 60_000));
        List<Proposal> expired = t.takeExpired(System.nanoTime() + 1_000_000L);
        assertEquals(1, expired.size());
        assertEquals("old", expired.get(0).requestId);
        assertEquals(1, t.snapshot().size());
        assertEquals("fresh", t.snapshot().get(0).requestId);
    }

    @Test
    public void clearReturnsEverythingPending() throws Exception {
        ProposalTracker t = new ProposalTracker();
        t.add(proposal("a", 60_000));
        t.add(proposal("b", 60_000));
        assertEquals(2, t.clear().size());
        assertTrue(t.snapshot().isEmpty());
    }
}
