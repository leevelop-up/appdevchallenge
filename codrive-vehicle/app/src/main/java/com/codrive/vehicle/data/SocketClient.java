package com.codrive.vehicle.data;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class SocketClient {
    public interface Listener {
        void onOpen();

        void onMessage(String type, JSONObject payload);

        void onClosed(String reason);
    }

    private static final long PING_SECONDS = 10;
    private static final long DEAD_AFTER_MS = 25_000;

    private final OkHttpClient http;
    private final String wsUrl;
    private final String token;
    private final Listener listener;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile WebSocket socket;
    private volatile long lastRx;
    private volatile boolean closed;
    private ScheduledFuture<?> pinger;

    public SocketClient(OkHttpClient http, String baseUrl, String token, Listener listener) {
        this.http = http;
        this.wsUrl = baseUrl.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/ws/vehicle";
        this.token = token;
        this.listener = listener;
    }

    public void connect() {
        Request req = new Request.Builder().url(wsUrl).header("Authorization", "Bearer " + token).build();
        socket = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                lastRx = System.currentTimeMillis();
                startPing();
                listener.onOpen();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                lastRx = System.currentTimeMillis();
                try {
                    JSONObject m = new JSONObject(text);
                    listener.onMessage(m.optString("type"), m.optJSONObject("payload") == null
                            ? new JSONObject() : m.optJSONObject("payload"));
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                fail("closed " + code);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                fail(response != null && response.code() == 401 ? "UNAUTHORIZED" : String.valueOf(t.getMessage()));
            }
        });
    }

    private synchronized void startPing() {
        if (pinger != null) {
            pinger.cancel(false);
        }
        pinger = timer.scheduleWithFixedDelay(() -> {
            if (closed) {
                return;
            }
            if (System.currentTimeMillis() - lastRx > DEAD_AFTER_MS) {
                fail("heartbeat timeout");
                return;
            }
            send("PING", new JSONObject());
        }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
    }

    public boolean send(String type, JSONObject payload) {
        WebSocket ws = socket;
        if (ws == null || closed) {
            return false;
        }
        try {
            return ws.send(new JSONObject().put("type", type).put("payload", payload).toString());
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void fail(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        if (pinger != null) {
            pinger.cancel(false);
        }
        try {
            WebSocket ws = socket;
            if (ws != null) {
                ws.cancel();
            }
        } catch (Exception ignored) {
        }
        timer.shutdown();
        listener.onClosed(reason);
    }

    public void close() {
        fail("local close");
    }
}
