package com.codrive.vehicle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.codrive.vehicle.data.ApiClient;
import com.codrive.vehicle.data.ApiError;
import com.codrive.vehicle.data.KeystoreIdentity;
import com.codrive.vehicle.data.Settings;
import com.codrive.vehicle.data.SocketClient;
import com.codrive.vehicle.gateway.MockVehicleGateway;
import com.codrive.vehicle.gateway.PleosVehicleGateway;
import com.codrive.vehicle.gateway.VehicleGateway;
import com.codrive.vehicle.model.Models;
import com.codrive.vehicle.model.ProposalTracker;
import com.codrive.vehicle.model.ScreenResolver;
import com.codrive.vehicle.model.ScreenResolver.Connection;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CoDriveController {
    private static final String TAG = "CoDriveController";

    public static final class UiState {
        public final ScreenResolver.Screen screen;
        public final Connection connection;
        public final Models.Snapshot snapshot;
        public final Models.IssuedToken qr;
        public final boolean qrOverlay;
        public final boolean driving;
        public final String mode;
        public final String message;
        public final java.util.List<ProposalTracker.Proposal> proposals;

        UiState(ScreenResolver.Screen screen, Connection connection, Models.Snapshot snapshot,
                Models.IssuedToken qr, boolean qrOverlay, boolean driving, String mode, String message,
                java.util.List<ProposalTracker.Proposal> proposals) {
            this.screen = screen;
            this.connection = connection;
            this.snapshot = snapshot;
            this.qr = qr;
            this.qrOverlay = qrOverlay;
            this.driving = driving;
            this.mode = mode;
            this.message = message;
            this.proposals = proposals;
        }
    }

    private final Context ctx;
    private final Settings settings;
    private final KeystoreIdentity identity;
    private final String launchId = UUID.randomUUID().toString();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final MutableLiveData<UiState> live = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ApiClient api;
    private SocketClient socket;
    private VehicleGateway gateway;
    private volatile String sessionId;
    private volatile Models.Snapshot snapshot;
    private volatile Models.IssuedToken qr;
    private volatile boolean qrOverlay;
    private volatile boolean driving;
    private volatile Connection connection = Connection.CONNECTING;
    private volatile String message;
    private volatile int generation;
    private volatile boolean superseded;

    private static final long PROPOSAL_TTL_MILLIS = 55_000;
    private final ProposalTracker proposals = new ProposalTracker();

    public CoDriveController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.settings = new Settings(ctx);
        this.identity = new KeystoreIdentity(ctx);
        timer.scheduleWithFixedDelay(() -> io.execute(this::tick), 3, 3, TimeUnit.SECONDS);
    }

    public LiveData<UiState> state() {
        return live;
    }

    public Settings settings() {
        return settings;
    }

    public void start() {
        final int gen = ++generation;
        io.execute(() -> bootstrap(gen));
    }

    private void bootstrap(int gen) {
        superseded = false;
        closeSocket();
        if (gateway != null) {
            gateway.release();
        }
        connection = Connection.CONNECTING;
        message = null;
        snapshot = null;
        qr = null;
        qrOverlay = false;
        proposals.clear();
        publish();
        try {
            gateway = Settings.MODE_PLEOS.equals(settings.mode()) ? new PleosVehicleGateway(ctx) : new MockVehicleGateway();
            gateway.start();
            api = new ApiClient(settings.serverUrl());
            ensureRegistered();
            api.authenticate(identity.installationId(settings.serverUrl()), identity);
            openAndSync();
            connectSocket(gen);
        } catch (Exception e) {
            if (gen == generation) {
                fail(e);
            }
        }
    }

    private void ensureRegistered() throws Exception {
        String url = settings.serverUrl();
        if (identity.installationId(url) == null) {
            String id = api.register(android.os.Build.MODEL == null ? "CO-DRIVE 차량" : android.os.Build.MODEL,
                    identity.publicKeyBase64());
            identity.saveInstallationId(url, id);
        }
    }

    private void openAndSync() throws IOExceptionWrapper {
        try {
            JSONObject o = api.openSession(launchId);
            sessionId = o.getString("sessionId");
            snapshot = new Models.Snapshot(o.getJSONObject("snapshot"));
            qr = null;
            ensureQr();
        } catch (Exception e) {
            throw new IOExceptionWrapper(e);
        }
    }

    private static final class IOExceptionWrapper extends Exception {
        IOExceptionWrapper(Exception cause) {
            super(cause);
        }
    }

    private void connectSocket(int gen) {
        socket = new SocketClient(api.httpClient(), api.baseUrl(), api.token(), new SocketClient.Listener() {
            @Override
            public void onOpen() {
                io.execute(() -> {
                    if (gen != generation) {
                        return;
                    }
                    reportCapabilities();
                    refreshSnapshot();
                    connection = Connection.CONNECTED;
                    message = null;
                    publish();
                });
            }

            @Override
            public void onMessage(String type, JSONObject payload) {
                io.execute(() -> {
                    if (gen == generation) {
                        onServerMessage(type, payload);
                    }
                });
            }

            @Override
            public void onClosed(String reason) {
                io.execute(() -> {
                    if (gen == generation && !superseded) {
                        reconnect(gen, reason);
                    }
                });
            }
        });
        socket.connect();
    }

    private void reconnect(int gen, String reason) {
        connection = Connection.RECONNECTING;
        message = "서버 연결이 끊겼습니다. 다시 연결하는 중... (" + reason + ")";
        publish();
        for (int attempt = 0; attempt < 30 && gen == generation; attempt++) {
            try {
                Thread.sleep(Math.min(1000L << Math.min(attempt, 4), 15_000));
                api.authenticate(identity.installationId(settings.serverUrl()), identity);
                openAndSync();
                connectSocket(gen);
                return;
            } catch (Exception e) {
                Log.w(TAG, "재연결 실패 " + attempt + ": " + e);
            }
        }
        if (gen == generation) {
            connection = Connection.ERROR;
            message = "서버에 연결할 수 없습니다. 네트워크를 확인하고 다시 시도해주세요.";
            publish();
        }
    }

    private void fail(Exception e) {
        Throwable c = e instanceof IOExceptionWrapper && e.getCause() != null ? e.getCause() : e;
        connection = Connection.ERROR;
        message = c instanceof ApiError ? ((ApiError) c).getMessage() : "서버에 연결할 수 없습니다. (" + c.getMessage() + ")";
        publish();
    }

    private void closeSocket() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    private void onServerMessage(String type, JSONObject p) {
        switch (type) {
            case "SESSION_SNAPSHOT" -> {
                try {
                    snapshot = new Models.Snapshot(p);
                    sessionId = snapshot.sessionId;
                    if (!snapshot.pending.isEmpty()) {
                        qrOverlay = false;
                    }
                    ensureQr();
                    publish();
                } catch (JSONException e) {
                    Log.w(TAG, "snapshot 파싱 실패", e);
                }
            }
            case "JOIN_REQUEST" -> {
                message = "새 참여 요청: " + p.optString("nickname");
                publish();
            }
            case "VEHICLE_COMMAND" -> runCommand(p);
            case "VEHICLE_STATE_REQUEST" -> sendState(p);
            case "SESSION_ENDED" -> onSessionEnded(p.optString("reason"));
            default -> {
            }
        }
    }

    private void onSessionEnded(String reason) {
        if ("VEHICLE_RESTART".equals(reason)) {
            superseded = true;
            closeSocket();
            connection = Connection.ERROR;
            message = "다른 CO-DRIVE 실행이 연결을 이어받았습니다.";
            publish();
            return;
        }

        proposals.clear();
        try {
            openAndSync();
            refreshSnapshot();
            message = null;
        } catch (Exception e) {
            fail(e);
            return;
        }
        publish();
    }

    private void runCommand(JSONObject p) {
        String requestId = p.optString("requestId");
        String action = p.optString("action");
        JSONObject params = p.optJSONObject("params") == null ? new JSONObject() : p.optJSONObject("params");
        if (action.startsWith("NAV_") && settings.confirmRouteChanges()) {
            proposals.add(new ProposalTracker.Proposal(requestId, p.optString("from"), action, params, PROPOSAL_TTL_MILLIS));
            try {
                send("COMMAND_ACK", new JSONObject().put("requestId", requestId).put("awaitingDriver", true));
            } catch (JSONException ignored) {
            }
            publish();
            return;
        }
        send("COMMAND_ACK", new JSONObject(Map.of("requestId", requestId)));
        executeAndReport(requestId, action, params);
    }

    private void executeAndReport(String requestId, String action, JSONObject params) {
        gateway.execute(action, params, r -> {
            try {
                JSONObject res = new JSONObject().put("requestId", requestId).put("status", r.status)
                        .put("simulated", r.simulated);
                if (r.errorCode != null) {
                    res.put("errorCode", r.errorCode);
                }
                if (r.message != null) {
                    res.put("message", r.message);
                }
                send("COMMAND_RESULT", res);
            } catch (JSONException ignored) {
            }
        });
    }

    private void sendState(JSONObject p) {
        String domain = p.optString("domain");
        String target = p.optString("targetRequestId");
        gateway.readState(domain, values -> {
            try {
                send("VEHICLE_STATE", new JSONObject().put("targetRequestId", target).put("domain", domain)
                        .put("values", new JSONObject(values)).put("simulated", "MOCK".equals(gateway.mode())));
            } catch (JSONException ignored) {
            }
        });
    }

    private void send(String type, JSONObject payload) {
        SocketClient s = socket;
        if (s != null) {
            s.send(type, payload);
        }
    }

    private static final int CAPABILITY_RETRIES = 6;
    private static final long CAPABILITY_RETRY_SECONDS = 4;

    private void reportCapabilities() {
        reportCapabilities(0);
    }

    private void reportCapabilities(int attempt) {
        VehicleGateway g = gateway;
        g.checkCapabilities(caps -> {
            boolean missing = false;
            for (VehicleGateway.Capability c : caps.values()) {
                missing |= c.isNoResponse();
            }
            if (missing && attempt < CAPABILITY_RETRIES) {
                final int gen = generation;
                timer.schedule(() -> io.execute(() -> {
                    if (gen == generation && gateway == g) {
                        reportCapabilities(attempt + 1);
                    }
                }), CAPABILITY_RETRY_SECONDS, TimeUnit.SECONDS);
            }
            try {
                JSONObject f = new JSONObject();
                for (Map.Entry<String, VehicleGateway.Capability> e : caps.entrySet()) {
                    JSONObject c = new JSONObject().put("status", e.getValue().status);
                    if (e.getValue().min != null) {
                        c.put("min", e.getValue().min);
                    }
                    if (e.getValue().max != null) {
                        c.put("max", e.getValue().max);
                    }
                    f.put(e.getKey(), c);
                    Log.i(TAG, e.getKey() + " → " + e.getValue().status + " [" + e.getValue().min + ", "
                            + e.getValue().max + "]" + (e.getValue().reason != null ? " : " + e.getValue().reason : ""));
                }
                send("CAPABILITY_REPORT", new JSONObject().put("mode", g.mode()).put("driving", driving)
                        .put("features", f));
            } catch (JSONException ignored) {
            }
        });
    }

    private void tick() {
        List<ProposalTracker.Proposal> expired = proposals.takeExpired(System.nanoTime());
        for (ProposalTracker.Proposal p : expired) {
            sendFailure(p.requestId, "PROPOSAL_EXPIRED", "운전자가 응답하지 않아 제안이 만료되었습니다.");
        }
        if (!expired.isEmpty()) {
            publish();
        }
        VehicleGateway g = gateway;
        if (g == null || connection != Connection.CONNECTED) {
            return;
        }
        if (g instanceof MockVehicleGateway) {
            ((MockVehicleGateway) g).setDriving(settings.mockDriving());
        }
        g.isDriving(d -> {
            if (d != driving) {
                Log.i(TAG, "주행 판단 변경: " + driving + " → " + d);
                driving = d;
                io.execute(() -> {
                    reportCapabilities();
                    publish();
                });
            }
        });
        ensureQr();
    }

    private void ensureQr() {
        Models.Snapshot s = snapshot;
        if (s == null || !"ACTIVE".equals(s.sessionStatus) || api == null) {
            return;
        }
        boolean needed = s.participants.isEmpty() && s.pending.isEmpty() || qrOverlay;
        if (needed && (qr == null || qr.isExpiredWithin(2000))) {
            issueQr();
        }
    }

    private void issueQr() {
        try {
            qr = api.issueJoinToken(sessionId);
            message = null;
        } catch (ApiError e) {
            message = "FULL".equals(e.code) ? "최대 인원이 연결되어 있습니다. 기존 연결을 해제한 뒤 추가하세요." : e.getMessage();
            qrOverlay = false;
        } catch (Exception e) {
            message = "QR 코드를 발급하지 못했습니다.";
        }
        publish();
    }

    private void refreshSnapshot() {
        try {
            snapshot = api.snapshot(sessionId);
            ensureQr();
        } catch (Exception e) {
            Log.w(TAG, "snapshot 조회 실패: " + e);
        }
    }

    public void refreshQr() {
        io.execute(this::issueQr);
    }

    public void addPassenger() {
        io.execute(() -> {
            qrOverlay = true;
            issueQr();
        });
    }

    public void closeQrOverlay() {
        io.execute(() -> {
            qrOverlay = false;
            publish();
        });
    }

    public void approve(String requestId) {
        guarded(() -> api.approve(sessionId, requestId));
    }

    public void reject(String requestId) {
        guarded(() -> api.reject(sessionId, requestId));
    }

    public void assignRole(String participantId, String role) {
        guarded(() -> api.assignRole(sessionId, participantId, role));
    }

    public void setAccess(String participantId, String feature, String access) {
        guarded(() -> api.setAccess(sessionId, participantId, feature, access));
    }

    public void removeParticipant(String participantId) {
        guarded(() -> api.remove(sessionId, participantId));
    }

    public void endAll() {
        guarded(() -> {
            api.endSession(sessionId, "USER_ENDED_ALL");
            qrOverlay = false;
        });
    }

    public void acceptProposal(String requestId) {
        io.execute(() -> {
            ProposalTracker.Proposal p = proposals.take(requestId);
            if (p == null) {
                return;
            }
            try {
                send("COMMAND_ACK", new JSONObject().put("requestId", requestId));
            } catch (JSONException ignored) {
            }
            executeAndReport(requestId, p.action, p.params);
            publish();
        });
    }

    public void declineProposal(String requestId) {
        io.execute(() -> {
            ProposalTracker.Proposal p = proposals.take(requestId);
            if (p != null) {
                sendFailure(requestId, "DECLINED_BY_DRIVER", "운전자가 거절했습니다.");
                publish();
            }
        });
    }

    private void sendFailure(String requestId, String code, String message) {
        try {
            send("COMMAND_RESULT", new JSONObject().put("requestId", requestId).put("status", "FAILED")
                    .put("errorCode", code).put("message", message).put("simulated", false));
        } catch (JSONException ignored) {
        }
    }

    public void retry() {
        start();
    }

    private interface Call {
        void run() throws Exception;
    }

    private void guarded(Call c) {
        io.execute(() -> {
            if (driving) {
                message = "주행 중에는 사용할 수 없습니다. 정차 후 이용해주세요.";
                publish();
                return;
            }
            try {
                c.run();
                message = null;
                refreshSnapshot();
            } catch (ApiError e) {
                message = e.getMessage();
            } catch (Exception e) {
                message = "요청을 처리하지 못했습니다. (" + e.getMessage() + ")";
            }
            publish();
        });
    }

    private void publish() {
        UiState st = new UiState(ScreenResolver.resolve(connection, snapshot), connection, snapshot, qr, qrOverlay,
                driving, gateway == null ? settings.mode() : gateway.mode(), message, proposals.snapshot());
        main.post(() -> live.setValue(st));
    }
}
