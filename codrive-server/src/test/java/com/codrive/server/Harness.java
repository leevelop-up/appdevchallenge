package com.codrive.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

final class Harness {
    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static volatile String prefix = "";

    private Harness() {
    }

    record Res(int status, JsonNode body) {
        String err() {
            return body == null ? "" : body.path("error").asText("");
        }
    }

    static Res call(int port, String method, String path, String bearer, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + prefix + path))
                    .header("Content-Type", "application/json");
            if (bearer != null) {
                b.header("Authorization", "Bearer " + bearer);
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body() == null || r.body().isBlank() ? null : M.readTree(r.body());
            return new Res(r.statusCode(), json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final class Sock implements WebSocket.Listener {
        final List<JsonNode> all = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder buf = new StringBuilder();
        private int cursor = 0;
        volatile boolean closed;
        volatile int closeCode = -1;
        WebSocket ws;

        static Sock connect(int port, String path, Map<String, String> headers) {
            Sock s = new Sock();
            WebSocket.Builder b = HTTP.newWebSocketBuilder();
            headers.forEach(b::header);
            s.ws = b.buildAsync(URI.create("ws://localhost:" + port + path), s).join();
            return s;
        }

        static boolean rejected(int port, String path, Map<String, String> headers) {
            try {
                Sock.connect(port, path, headers);
                return false;
            } catch (Exception e) {
                return true;
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                try {
                    all.add(M.readTree(buf.toString()));
                } catch (Exception ignored) {
                }
                buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            closeCode = statusCode;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
        }

        void send(String type, Object payload) {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", type);
                m.put("payload", payload == null ? Map.of() : payload);
                ws.sendText(M.writeValueAsString(m), true).join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void drop() {
            ws.abort();
        }

        JsonNode await(String type, Predicate<JsonNode> cond) {
            JsonNode r = tryAwait(type, cond, 5000);
            if (r == null) {
                throw new AssertionError("기다린 메시지가 오지 않음: " + type + " / 받은 메시지: " + summary());
            }
            return r;
        }

        JsonNode await(String type) {
            return await(type, n -> true);
        }

        JsonNode tryAwait(String type, Predicate<JsonNode> cond, long ms) {
            long end = System.currentTimeMillis() + ms;
            do {
                synchronized (all) {
                    for (int i = cursor; i < all.size(); i++) {
                        JsonNode m = all.get(i);
                        if (m.path("type").asText().equals(type) && cond.test(m.path("payload"))) {
                            cursor = i + 1;
                            return m.path("payload");
                        }
                    }
                }
                sleep(25);
            } while (System.currentTimeMillis() < end);
            return null;
        }

        boolean sawNone(String type, long ms) {
            return tryAwait(type, n -> true, ms) == null;
        }

        String summary() {
            synchronized (all) {
                return all.stream().map(m -> m.path("type").asText()).toList().toString();
            }
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class Veh {
        final int port;
        final KeyPair keys;
        UUID installationId;
        String token;
        UUID sessionId;
        String launchId;
        Sock sock;

        Veh(int port) {
            try {
                this.port = port;
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(256);
                this.keys = g.generateKeyPair();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static Veh registered(int port) {
            Veh v = new Veh(port);
            Res r = call(port, "POST", "/api/vehicle/register", null, Map.of("displayName", "테스트 차량",
                    "publicKey", Base64.getEncoder().encodeToString(v.keys.getPublic().getEncoded())));
            v.installationId = UUID.fromString(r.body().path("installationId").asText());
            v.authenticate();
            return v;
        }

        void authenticate() {
            String nonce = call(port, "POST", "/api/vehicle/challenge", null,
                    Map.of("installationId", installationId)).body().path("nonce").asText();
            this.token = call(port, "POST", "/api/vehicle/verify", null, Map.of("installationId", installationId,
                    "nonce", nonce, "signature", sign(nonce))).body().path("accessToken").asText();
        }

        String sign(String nonce) {
            try {
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initSign(keys.getPrivate());
                s.update(nonce.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(s.sign());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        JsonNode open(String launchId) {
            Res r = call(port, "POST", "/api/vehicle/sessions/open", token, Map.of("launchId", launchId));
            if (r.status() != 200) {
                throw new AssertionError("open 실패 " + r.status() + " " + r.body());
            }
            sessionId = UUID.fromString(r.body().path("sessionId").asText());
            this.launchId = launchId;
            return r.body();
        }

        void connect() {
            sock = Sock.connect(port, "/ws/vehicle", Map.of("Authorization", "Bearer " + token));
            sock.await("HELLO");
        }

        void reportCaps() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("NAV_DESTINATION", Map.of("status", "MOCK_ONLY"));
            f.put("NAV_WAYPOINT", Map.of("status", "MOCK_ONLY"));
            f.put("HVAC_VIEW", Map.of("status", "AVAILABLE"));
            f.put("HVAC_TEMPERATURE", Map.of("status", "MOCK_ONLY", "min", 18, "max", 28));
            f.put("HVAC_FAN", Map.of("status", "MOCK_ONLY", "min", 0, "max", 8));
            f.put("LIGHT_VIEW", Map.of("status", "AVAILABLE"));
            f.put("LIGHT_BRIGHTNESS", Map.of("status", "MOCK_ONLY", "min", 0, "max", 100));
            f.put("LIGHT_COLOR", Map.of("status", "NOT_SUPPORTED"));
            f.put("MUSIC_VIEW", Map.of("status", "MOCK_ONLY"));
            f.put("MUSIC_CONTROL", Map.of("status", "MOCK_ONLY"));
            sock.send("CAPABILITY_REPORT", Map.of("mode", "MOCK", "driving", false, "features", f));
        }

        record Issued(String token, String code, String url) {
        }

        Issued issue() {
            Res r = call(port, "POST", "/api/vehicle/sessions/" + sessionId + "/join-tokens", token, null);
            if (r.status() != 200) {
                throw new AssertionError("issue 실패 " + r.status() + " " + r.body());
            }
            return new Issued(r.body().path("token").asText(), r.body().path("code").asText(),
                    r.body().path("joinUrl").asText());
        }

        Res req(String method, String path) {
            return Harness.call(port, method, path, token, null);
        }

        JsonNode snapshot() {
            return call(port, "GET", "/api/vehicle/sessions/" + sessionId + "/snapshot", token, null).body();
        }

        Res approve(UUID requestId) {
            return call(port, "POST", "/api/vehicle/sessions/" + sessionId + "/requests/" + requestId + "/approve", token, null);
        }

        Res role(String participantId, String role) {
            return call(port, "PUT", "/api/vehicle/sessions/" + sessionId + "/participants/" + participantId + "/role",
                    token, Map.of("role", role));
        }

        Res access(String participantId, String feature, String access) {
            return call(port, "PUT", "/api/vehicle/sessions/" + sessionId + "/participants/" + participantId
                    + "/permissions/" + feature, token, Map.of("access", access));
        }

        Res remove(String participantId) {
            return call(port, "DELETE", "/api/vehicle/sessions/" + sessionId + "/participants/" + participantId, token, null);
        }

        void autoApply() {
            new Thread(() -> {
                while (!sock.closed) {
                    JsonNode cmd = sock.tryAwait("VEHICLE_COMMAND", n -> true, 200);
                    if (cmd != null) {
                        sock.send("COMMAND_ACK", Map.of("requestId", cmd.path("requestId").asText()));
                        sock.send("COMMAND_RESULT", Map.of("requestId", cmd.path("requestId").asText(),
                                "status", "APPLIED", "simulated", true));
                    }
                }
            }, "fake-vehicle").start();
        }
    }

    static final class Phone {
        final int port;
        String nickname;
        UUID requestId;
        String token;
        Sock sock;

        Phone(int port) {
            this.port = port;
        }

        Res join(String joinToken, String nick) {
            nickname = nick;
            Res r = call(port, "POST", "/api/join/requests", null, Map.of("token", joinToken, "nickname", nick));
            if (r.status() == 201) {
                requestId = UUID.fromString(r.body().path("requestId").asText());
                token = r.body().path("participantToken").asText();
            }
            return r;
        }

        JsonNode connect() {
            sock = Sock.connect(port, "/ws/participant", Map.of());
            sock.send("AUTH", Map.of("token", token));
            return sock.await("AUTH_OK");
        }

        void command(String action, Map<String, Object> params) {
            sock.send("VEHICLE_COMMAND", Map.of("action", action, "params", params));
        }
    }
}
