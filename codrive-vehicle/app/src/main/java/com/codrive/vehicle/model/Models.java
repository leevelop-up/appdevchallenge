package com.codrive.vehicle.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() {
    }

    public static final class FeatureInfo {
        public final String code;
        public final String kind;
        public final String access;
        public final String status;

        FeatureInfo(JSONObject o) {
            code = o.optString("code");
            kind = o.optString("kind");
            access = o.optString("access");
            status = o.optString("status");
        }

        public boolean isUsable() {
            if ("VIEW".equals(kind)) {
                return "AVAILABLE".equals(status) || "VIEW_ONLY".equals(status) || "MOCK_ONLY".equals(status);
            }
            return "AVAILABLE".equals(status) || "MOCK_ONLY".equals(status);
        }
    }

    public static final class ParticipantInfo {
        public final String id;
        public final String nickname;
        public final String role;
        public final String connection;
        public final List<FeatureInfo> features = new ArrayList<>();

        ParticipantInfo(JSONObject o) throws JSONException {
            id = o.getString("participantId");
            nickname = o.optString("nickname");
            role = o.optString("role", "NONE");
            connection = o.optString("connectionStatus");
            JSONArray fa = o.optJSONArray("features");
            for (int i = 0; fa != null && i < fa.length(); i++) {
                features.add(new FeatureInfo(fa.getJSONObject(i)));
            }
        }

        public boolean isOnline() {
            return "ONLINE".equals(connection);
        }
    }

    public static final class PendingInfo {
        public final String requestId;
        public final String nickname;

        PendingInfo(JSONObject o) {
            requestId = o.optString("requestId");
            nickname = o.optString("nickname");
        }
    }

    public static final class Snapshot {
        public final String sessionId;
        public final String alias;
        public final String sessionStatus;
        public final int maxParticipants;
        public final String capabilityMode;
        public final boolean driving;
        public final List<ParticipantInfo> participants = new ArrayList<>();
        public final List<PendingInfo> pending = new ArrayList<>();

        public Snapshot(JSONObject o) throws JSONException {
            sessionId = o.getString("sessionId");
            alias = o.optString("alias");
            sessionStatus = o.optString("sessionStatus", "ACTIVE");
            maxParticipants = o.optInt("maxParticipants", 3);
            capabilityMode = o.optString("capabilityMode", "UNKNOWN");
            driving = o.optBoolean("driving", false);
            JSONArray pa = o.optJSONArray("participants");
            for (int i = 0; pa != null && i < pa.length(); i++) {
                participants.add(new ParticipantInfo(pa.getJSONObject(i)));
            }
            JSONArray qa = o.optJSONArray("pendingRequests");
            for (int i = 0; qa != null && i < qa.length(); i++) {
                pending.add(new PendingInfo(qa.getJSONObject(i)));
            }
        }

        public boolean hasUnassigned() {
            for (ParticipantInfo p : participants) {
                if ("NONE".equals(p.role)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class IssuedToken {
        public final String code;
        public final String joinUrl;
        private final long expiresAtNanos;

        public IssuedToken(JSONObject o) {
            code = o.optString("code");
            joinUrl = o.optString("joinUrl");
            long ttl = o.optLong("ttlSeconds", 60);
            expiresAtNanos = System.nanoTime() + ttl * 1_000_000_000L;
        }

        public long remainingMillis() {
            return Math.max(0, (expiresAtNanos - System.nanoTime()) / 1_000_000L);
        }

        public boolean isExpiredWithin(long marginMillis) {
            return remainingMillis() <= marginMillis;
        }
    }
}
