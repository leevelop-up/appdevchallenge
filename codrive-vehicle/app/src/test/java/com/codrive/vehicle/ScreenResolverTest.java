package com.codrive.vehicle;

import static org.junit.Assert.assertEquals;

import com.codrive.vehicle.model.Models;
import com.codrive.vehicle.model.ScreenResolver;
import com.codrive.vehicle.model.ScreenResolver.Connection;
import com.codrive.vehicle.model.ScreenResolver.Screen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ScreenResolverTest {
    private static Models.Snapshot snapshot(String sessionStatus, int participants) throws Exception {
        JSONArray ps = new JSONArray();
        for (int i = 0; i < participants; i++) {
            ps.put(new JSONObject().put("participantId", "p" + i).put("nickname", "n" + i)
                    .put("role", "MAP").put("connectionStatus", "ONLINE").put("features", new JSONArray()));
        }
        return new Models.Snapshot(new JSONObject().put("sessionId", "s").put("alias", "A")
                .put("sessionStatus", sessionStatus).put("maxParticipants", 3).put("participants", ps)
                .put("pendingRequests", new JSONArray()));
    }

    @Test
    public void noUsersShowsQr() throws Exception {
        assertEquals(Screen.QR, ScreenResolver.resolve(Connection.CONNECTED, snapshot("ACTIVE", 0)));
    }

    @Test
    public void connectedUsersShowUserList() throws Exception {
        assertEquals(Screen.USERS, ScreenResolver.resolve(Connection.CONNECTED, snapshot("ACTIVE", 2)));
    }

    @Test
    public void neverShowsQrBeforeServerAuthIsDone() throws Exception {
        assertEquals(Screen.CONNECTING, ScreenResolver.resolve(Connection.CONNECTING, null));
        assertEquals(Screen.CONNECTING, ScreenResolver.resolve(Connection.CONNECTING, snapshot("ACTIVE", 0)));
    }

    @Test
    public void serverErrorShowsErrorInsteadOfDeadQr() throws Exception {
        assertEquals(Screen.ERROR, ScreenResolver.resolve(Connection.ERROR, snapshot("ACTIVE", 0)));
        assertEquals(Screen.ERROR, ScreenResolver.resolve(Connection.ERROR, null));
    }

    @Test
    public void reconnectingOrSuspendedSessionDoesNotExposeQr() throws Exception {
        assertEquals(Screen.CONNECTING, ScreenResolver.resolve(Connection.RECONNECTING, snapshot("ACTIVE", 0)));
        assertEquals(Screen.CONNECTING, ScreenResolver.resolve(Connection.CONNECTED, snapshot("SUSPENDED", 0)));
    }
}
