package com.codrive.vehicle.data;

import com.codrive.vehicle.model.Models;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
    private final String baseUrl;
    private volatile String token;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public OkHttpClient httpClient() {
        return http;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String token() {
        return token;
    }

    public void setToken(String t) {
        this.token = t;
    }

    public String register(String displayName, String publicKeyBase64) throws IOException, ApiError, JSONException {
        JSONObject r = call("POST", "/api/vehicle/register", new JSONObject()
                .put("displayName", displayName).put("publicKey", publicKeyBase64), false);
        return r.getString("installationId");
    }

    public void authenticate(String installationId, KeystoreIdentity identity) throws Exception {
        String nonce = call("POST", "/api/vehicle/challenge", new JSONObject().put("installationId", installationId),
                false).getString("nonce");
        JSONObject r = call("POST", "/api/vehicle/verify", new JSONObject().put("installationId", installationId)
                .put("nonce", nonce).put("signature", identity.sign(nonce)), false);
        token = r.getString("accessToken");
    }

    public JSONObject openSession(String launchId) throws IOException, ApiError, JSONException {
        return call("POST", "/api/vehicle/sessions/open", new JSONObject().put("launchId", launchId), true);
    }

    public Models.Snapshot snapshot(String sessionId) throws IOException, ApiError, JSONException {
        return new Models.Snapshot(call("GET", "/api/vehicle/sessions/" + sessionId + "/snapshot", null, true));
    }

    public Models.IssuedToken issueJoinToken(String sessionId) throws IOException, ApiError, JSONException {
        return new Models.IssuedToken(call("POST", "/api/vehicle/sessions/" + sessionId + "/join-tokens", null, true));
    }

    public void approve(String sessionId, String requestId) throws IOException, ApiError, JSONException {
        call("POST", "/api/vehicle/sessions/" + sessionId + "/requests/" + requestId + "/approve", null, true);
    }

    public void reject(String sessionId, String requestId) throws IOException, ApiError, JSONException {
        call("POST", "/api/vehicle/sessions/" + sessionId + "/requests/" + requestId + "/reject", null, true);
    }

    public void assignRole(String sessionId, String participantId, String role) throws IOException, ApiError, JSONException {
        call("PUT", "/api/vehicle/sessions/" + sessionId + "/participants/" + participantId + "/role",
                new JSONObject().put("role", role), true);
    }

    public void setAccess(String sessionId, String participantId, String feature, String access)
            throws IOException, ApiError, JSONException {
        call("PUT", "/api/vehicle/sessions/" + sessionId + "/participants/" + participantId + "/permissions/" + feature,
                new JSONObject().put("access", access), true);
    }

    public void remove(String sessionId, String participantId) throws IOException, ApiError, JSONException {
        call("DELETE", "/api/vehicle/sessions/" + sessionId + "/participants/" + participantId, null, true);
    }

    public void endSession(String sessionId, String reason) throws IOException, ApiError, JSONException {
        call("POST", "/api/vehicle/sessions/" + sessionId + "/end", new JSONObject().put("reason", reason), true);
    }

    private JSONObject call(String method, String path, JSONObject body, boolean auth)
            throws IOException, ApiError, JSONException {
        Request.Builder b = new Request.Builder().url(baseUrl + path);
        if (auth && token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        RequestBody rb = body == null ? (method.equals("GET") ? null : RequestBody.create("", JSON))
                : RequestBody.create(body.toString(), JSON);
        b.method(method, rb);
        try (Response r = http.newCall(b.build()).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            if (!r.isSuccessful()) {
                String code = "HTTP_" + r.code(), msg = "서버 오류 (" + r.code() + ")";
                try {
                    JSONObject e = new JSONObject(text);
                    code = e.optString("error", code);
                    msg = e.optString("message", msg);
                } catch (JSONException ignored) {
                }
                if (r.code() == 401 || (r.code() == 403 && text.isEmpty())) {
                    code = "UNAUTHORIZED";
                }
                throw new ApiError(r.code(), code, msg);
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        }
    }
}
