package com.codrive.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.codrive.vehicle.gateway.MockVehicleGateway;
import com.codrive.vehicle.gateway.VehicleGateway;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class MockVehicleGatewayTest {
    private static <T> T await(java.util.function.Consumer<VehicleGateway.Callback<T>> call) {
        AtomicReference<T> ref = new AtomicReference<>();
        call.accept(ref::set);
        return ref.get();
    }

    @Test
    public void everyCapabilityIsReportedAsMockOnly() {
        MockVehicleGateway g = new MockVehicleGateway();
        Map<String, VehicleGateway.Capability> caps = await(g::checkCapabilities);
        assertEquals(10, caps.size());
        for (VehicleGateway.Capability c : caps.values()) {
            assertEquals("실제 차량 지원으로 보고되면 안 된다", "MOCK_ONLY", c.status);
        }
        assertEquals("MOCK", g.mode());
    }

    @Test
    public void mockResultsAreAlwaysMarkedSimulated() throws Exception {
        MockVehicleGateway g = new MockVehicleGateway();
        VehicleGateway.Result r = await(cb -> g.execute("HVAC_TEMPERATURE", jo("celsius", 25.5), cb));
        assertEquals("APPLIED", r.status);
        assertTrue("MOCK 결과는 simulated=true 여야 서버/웹에서 실제 제어로 표시되지 않는다", r.simulated);
        Map<String, Object> st = await(cb -> g.readState("HVAC", cb));
        assertEquals(25.5, (Double) st.get("temperature"), 0.001);
    }

    @Test
    public void injectedFailureIsReportedAsFailedNotApplied() throws Exception {
        MockVehicleGateway g = new MockVehicleGateway();
        g.failNext();
        VehicleGateway.Result r = await(cb -> g.execute("HVAC_FAN", jo("level", 5), cb));
        assertEquals("FAILED", r.status);
        assertNotNull(r.errorCode);
        VehicleGateway.Result next = await(cb -> g.execute("HVAC_FAN", jo("level", 5), cb));
        assertEquals("실패 주입은 한 번만 적용", "APPLIED", next.status);
    }

    @Test
    public void musicControlChangesMockState() throws Exception {
        MockVehicleGateway g = new MockVehicleGateway();
        VehicleGateway.Result play = await(cb -> g.execute("MUSIC_CONTROL", jo("command", "PLAY"), cb));
        assertEquals("APPLIED", play.status);
        Map<String, Object> st = await(cb -> g.readState("MUSIC", cb));
        assertEquals(true, st.get("playing"));
        assertEquals("Mock Track 1", st.get("title"));
        VehicleGateway.Result next = await(cb -> g.execute("MUSIC_CONTROL", jo("command", "NEXT"), cb));
        assertTrue(next.simulated);
        st = await(cb -> g.readState("MUSIC", cb));
        assertEquals("Mock Track 2", st.get("title"));
        VehicleGateway.Result bad = await(cb -> g.execute("MUSIC_CONTROL", jo("command", "VOLUME_UP"), cb));
        assertEquals("NOT_SUPPORTED", bad.status);
    }

    @Test
    public void noResponseIsDistinguishedFromRealUnsupported() {
        assertTrue(VehicleGateway.Capability.noResponse().isNoResponse());
        assertFalse(VehicleGateway.Capability.unsupported("미지원").isNoResponse());
        assertFalse(VehicleGateway.Capability.of("MOCK_ONLY").isNoResponse());
    }

    @Test
    public void unknownActionIsNotSupported() throws Exception {
        MockVehicleGateway g = new MockVehicleGateway();
        VehicleGateway.Result r = await(cb -> g.execute("OPEN_TRUNK", new JSONObject(), cb));
        assertEquals("NOT_SUPPORTED", r.status);
    }

    @Test
    public void drivingFlagFollowsSwitch() {
        MockVehicleGateway g = new MockVehicleGateway();
        assertFalse(await(g::isDriving));
        g.setDriving(true);
        assertTrue(await(g::isDriving));
    }

    private static JSONObject jo(String k, Object v) {
        try {
            return new JSONObject().put(k, v);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
