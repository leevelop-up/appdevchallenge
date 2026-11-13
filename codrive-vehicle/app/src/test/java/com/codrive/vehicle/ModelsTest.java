package com.codrive.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codrive.vehicle.model.Models;
import com.codrive.vehicle.ui.Labels;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ModelsTest {
    private static JSONObject feature(String code, String kind, String access, String status) throws Exception {
        return new JSONObject().put("code", code).put("kind", kind).put("access", access).put("status", status);
    }

    private static Models.ParticipantInfo participant(JSONObject... features) throws Exception {
        JSONArray fa = new JSONArray();
        for (JSONObject f : features) {
            fa.put(f);
        }
        Models.Snapshot s = new Models.Snapshot(new JSONObject().put("sessionId", "s").put("participants",
                new JSONArray().put(new JSONObject().put("participantId", "p").put("nickname", "지연")
                        .put("role", "HVAC").put("connectionStatus", "ONLINE").put("features", fa))));
        return s.participants.get(0);
    }

    @Test
    public void unusableFeaturesAreNotListedAsShared() throws Exception {
        Models.ParticipantInfo p = participant(
                feature("HVAC_VIEW", "VIEW", "VIEW", "AVAILABLE"),
                feature("HVAC_TEMPERATURE", "CONTROL", "NONE", "PERMISSION_REQUIRED"),
                feature("HVAC_FAN", "CONTROL", "CONTROL", "NOT_SUPPORTED"));
        String[] s = Labels.summarize(p);
        assertEquals("허용 기능: 공조 상태 조회", s[0]);
        assertEquals("권한 확인 필요: 온도 변경", s[1]);
        assertTrue(s[2].contains("풍량 변경") && s[2].contains("미지원"));
    }

    @Test
    public void mockOnlyIsMarkedSoItIsNotMistakenForRealControl() throws Exception {
        Models.ParticipantInfo p = participant(feature("HVAC_TEMPERATURE", "CONTROL", "CONTROL", "MOCK_ONLY"));
        assertEquals("허용 기능: 온도 변경(MOCK)", Labels.summarize(p)[0]);
        assertTrue(p.features.get(0).isUsable());
    }

    @Test
    public void viewOnlyAndBlockedAreHandledPerKind() throws Exception {
        Models.ParticipantInfo p = participant(
                feature("HVAC_VIEW", "VIEW", "VIEW", "VIEW_ONLY"),
                feature("HVAC_TEMPERATURE", "CONTROL", "CONTROL", "VIEW_ONLY"),
                feature("HVAC_FAN", "CONTROL", "CONTROL", "TEMPORARILY_BLOCKED"));
        assertTrue(p.features.get(0).isUsable());
        assertFalse("조회만 가능한 상태는 제어 기능으로 쓸 수 없다", p.features.get(1).isUsable());
        assertFalse(p.features.get(2).isUsable());
    }

    @Test
    public void unassignedParticipantIsDetected() throws Exception {
        Models.Snapshot s = new Models.Snapshot(new JSONObject().put("sessionId", "s").put("participants",
                new JSONArray().put(new JSONObject().put("participantId", "p").put("nickname", "a").put("role", "NONE"))));
        assertTrue(s.hasUnassigned());
        assertEquals("역할 미배정", Labels.role("NONE"));
    }

    @Test
    public void tokenExpiryUsesServerTtlNotDeviceClock() throws Exception {
        Models.IssuedToken t = new Models.IssuedToken(new JSONObject().put("code", "AB12CD")
                .put("joinUrl", "https://x/join?token=t").put("ttlSeconds", 300)
                .put("expiresAt", "2020-01-01T00:00:00Z"));
        long rem = t.remainingMillis();
        assertTrue(rem > 298_000 && rem <= 300_000);
        assertFalse(t.isExpiredWithin(2000));
        Models.IssuedToken expired = new Models.IssuedToken(new JSONObject().put("ttlSeconds", 0));
        assertTrue(expired.isExpiredWithin(0));
    }
}
