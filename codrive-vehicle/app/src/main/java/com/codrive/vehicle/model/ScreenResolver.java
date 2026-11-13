package com.codrive.vehicle.model;

public final class ScreenResolver {
    private ScreenResolver() {
    }

    public enum Connection { CONNECTING, CONNECTED, RECONNECTING, ERROR }

    public enum Screen {
        CONNECTING,

        ERROR,

        QR,

        USERS
    }

    public static Screen resolve(Connection conn, Models.Snapshot s) {
        if (conn == Connection.ERROR) {
            return Screen.ERROR;
        }
        if (conn != Connection.CONNECTED || s == null) {
            return Screen.CONNECTING;
        }
        if (!"ACTIVE".equals(s.sessionStatus)) {
            return Screen.CONNECTING;
        }
        return s.participants.isEmpty() ? Screen.QR : Screen.USERS;
    }
}
