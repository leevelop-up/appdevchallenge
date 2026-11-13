package com.codrive.server;

import com.codrive.server.Harness.*;
import com.codrive.server.service.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CoDriveFlowTest {
    @LocalServerPort
    int port;
    @Autowired
    RateLimiter limiter;

    @BeforeEach
    void reset() {
        limiter.reset();
    }

    private Veh readyVehicle() {
        Veh v = Veh.registered(port);
        v.open("launch-" + UUID.randomUUID());
        v.connect();
        v.reportCaps();
        return v;
    }

    private String joined(Veh v, Phone p, String nick, String role) {
        Veh.Issued q = v.issue();
        assertThat(p.join(q.token(), nick).status()).isEqualTo(201);
        v.sock.await("JOIN_REQUEST", n -> n.path("nickname").asText().equals(nick));
        p.connect();
        assertThat(v.approve(p.requestId).status()).isEqualTo(200);
        p.sock.await("JOIN_APPROVED");
        String pid = null;
        for (JsonNode x : v.snapshot().path("participants")) {
            if (x.path("nickname").asText().equals(nick)) {
                pid = x.path("participantId").asText();
            }
        }
        assertThat(pid).isNotNull();
        assertThat(v.role(pid, role).status()).isEqualTo(200);
        p.sock.await("ROLE_ASSIGNED");
        return pid;
    }

    @Test
    @DisplayName("1. 첫 실행: 세션이 만들어지고 QR(참여 URL)과 참여 코드가 발급된다")
    void firstLaunchCreatesSessionAndQr() {
        Veh v = Veh.registered(port);
        JsonNode open = v.open("launch-1");
        assertThat(open.path("created").asBoolean()).isTrue();
        assertThat(open.path("snapshot").path("participants")).isEmpty();
        Veh.Issued q = v.issue();
        assertThat(q.url()).startsWith("http://localhost/join?token=").endsWith(q.token());
        assertThat(q.code()).hasSize(6);
    }

    @Test
    @DisplayName("2~4,9,10,17,19. 참여 요청→승인→역할→권한 없는 제어 차단→권한 부여→MOCK 적용")
    void fullFlowWithPermissionAndMock() {
        Veh v = readyVehicle();
        v.autoApply();
        Phone p = new Phone(port);
        String pid = joined(v, p, "지연", "HVAC");

        JsonNode st = p.sock.await("STATE_UPDATE", n -> n.path("role").asText().equals("HVAC"));
        assertThat(feature(st, "HVAC_VIEW")).isEqualTo("AVAILABLE");
        assertThat(feature(st, "HVAC_TEMPERATURE")).isEqualTo("PERMISSION_REQUIRED");
        assertThat(st.path("capabilityMode").asText()).isEqualTo("MOCK");

        p.command("HVAC_TEMPERATURE", Map.of("celsius", 24));
        JsonNode denied = p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("PERMISSION_DENIED"));
        assertThat(denied.path("action").asText()).isEqualTo("HVAC_TEMPERATURE");
        assertThat(v.sock.sawNone("VEHICLE_COMMAND", 300)).isTrue();

        assertThat(v.access(pid, "HVAC_TEMPERATURE", "CONTROL").status()).isEqualTo(200);
        p.sock.await("STATE_UPDATE", n -> "MOCK_ONLY".equals(feature(n, "HVAC_TEMPERATURE")));

        p.command("HVAC_TEMPERATURE", Map.of("celsius", 40));
        p.sock.await("ERROR", n -> n.path("code").asText().equals("INVALID_PARAMS"));

        p.command("HVAC_TEMPERATURE", Map.of("celsius", 24));
        JsonNode applied = p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("APPLIED"));
        assertThat(applied.path("simulated").asBoolean()).as("MOCK 결과는 실제 적용으로 표시되면 안 됨").isTrue();
    }

    @Test
    @DisplayName("5,8. 동승자 3명이 각자 다른 역할을 쓰고, 4번째는 거부되며, 한 명이 나가면 목록이 갱신된다")
    void threeParticipantsAndLeave() {
        Veh v = readyVehicle();
        Phone a = new Phone(port), b = new Phone(port), c = new Phone(port);
        joined(v, a, "민수", "MAP");
        joined(v, b, "지연", "HVAC");
        joined(v, c, "서준", "LIGHT");

        JsonNode snap = v.snapshot();
        assertThat(snap.path("participants")).hasSize(3);
        assertThat(v.req("POST", "/api/vehicle/sessions/" + v.sessionId + "/join-tokens").err()).isEqualTo("FULL");

        a.sock.send("LEAVE", Map.of());
        v.sock.await("SESSION_SNAPSHOT", n -> n.path("participants").size() == 2
                && java.util.stream.StreamSupport.stream(n.path("participants").spliterator(), false)
                .noneMatch(x -> x.path("nickname").asText().equals("민수")));
        assertThat(v.issue().token()).isNotBlank();
    }

    @Test
    @DisplayName("6,7. 앱 화면을 나갔다 돌아오면(같은 launchId) 같은 세션을 돌려주고 연결 정보를 유지한다")
    void relaunchSameProcessKeepsSession() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        joined(v, p, "민수", "MAP");
        UUID before = v.sessionId;

        JsonNode again = Harness.call(port, "POST", "/api/vehicle/sessions/open", v.token,
                Map.of("launchId", v.launchId)).body();
        assertThat(again.path("created").asBoolean()).isFalse();
        assertThat(UUID.fromString(again.path("sessionId").asText())).isEqualTo(before);
        assertThat(again.path("snapshot").path("participants")).hasSize(1);
    }

    @Test
    @DisplayName("13,15,16. 앱이 완전히 재실행되면 이전 세션·QR·동승자 권한이 폐기되고 자동 복구되지 않는다")
    void fullRestartDoesNotRestore() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        joined(v, p, "민수", "MAP");
        Veh.Issued oldQr = v.issue();
        UUID oldSession = v.sessionId;

        JsonNode fresh = v.open("launch-after-restart");
        assertThat(fresh.path("created").asBoolean()).isTrue();
        assertThat(UUID.fromString(fresh.path("sessionId").asText())).isNotEqualTo(oldSession);
        assertThat(fresh.path("snapshot").path("participants")).isEmpty();

        JsonNode ended = p.sock.await("SESSION_ENDED");
        assertThat(ended.path("reason").asText()).isEqualTo("VEHICLE_RESTART");

        Res reuse = new Phone(port).join(oldQr.token(), "새사람");
        assertThat(reuse.status()).isEqualTo(409);

        Phone ghost = new Phone(port);
        ghost.token = p.token;
        ghost.requestId = p.requestId;
        JsonNode state = ghost.connect();
        assertThat(state.path("status").asText()).isEqualTo("ENDED");
        ghost.command("NAV_DESTINATION", Map.of("name", "서울역", "lat", 37.55, "lng", 126.97));
        ghost.sock.await("ERROR", n -> n.path("code").asText().equals("NOT_APPROVED"));
    }

    @Test
    @DisplayName("11. 차량 A·B 세션이 격리된다")
    void vehiclesAreIsolated() {
        Veh a = readyVehicle(), b = readyVehicle();
        a.autoApply();
        Phone pa = new Phone(port);
        String pid = joined(a, pa, "민수", "MAP");

        pa.command("NAV_DESTINATION", Map.of("name", "서울역", "lat", 37.55, "lng", 126.97));
        pa.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("APPLIED"));
        assertThat(b.sock.sawNone("VEHICLE_COMMAND", 300)).as("차량 B 는 A 의 명령을 받지 않는다").isTrue();

        Res r = Harness.call(port, "PUT", "/api/vehicle/sessions/" + a.sessionId + "/participants/" + pid + "/role",
                b.token, Map.of("role", "HVAC"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.err()).isEqualTo("NOT_YOUR_SESSION");
    }

    @Test
    @DisplayName("12. 네트워크 단절: 제어 중단→유예시간 내 재연결로 복구, 유예시간 초과 시 세션 종료")
    void networkDropAndGrace() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        String pid = joined(v, p, "민수", "MAP");

        v.sock.drop();
        p.sock.await("SESSION_SUSPENDED");
        p.command("NAV_DESTINATION", Map.of("name", "서울역", "lat", 37.55, "lng", 126.97));
        p.sock.await("ERROR", n -> n.path("code").asText().equals("SESSION_SUSPENDED"));

        v.authenticate();
        v.connect();
        p.sock.await("VEHICLE_RECONNECTED");
        assertThat(v.snapshot().path("participants")).hasSize(1);

        v.sock.drop();
        p.sock.await("SESSION_SUSPENDED");
        JsonNode ended = p.sock.await("SESSION_ENDED");
        assertThat(ended.path("reason").asText()).isEqualTo("NETWORK_TIMEOUT");
        assertThat(pid).isNotNull();
    }

    @Test
    @DisplayName("14. 전원 OFF 이벤트: 세션 종료, 권한 폐기, 휴대폰에 SESSION_ENDED, QR 무효화")
    void powerOffEndsSession() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        joined(v, p, "민수", "MAP");
        Veh.Issued qr = v.issue();

        v.sock.send("POWER_OFF", Map.of());
        assertThat(p.sock.await("SESSION_ENDED").path("reason").asText()).isEqualTo("POWER_OFF");
        assertThat(new Phone(port).join(qr.token(), "새사람").status()).isEqualTo(409);
    }

    @Test
    @DisplayName("QR 은 일회용이며 참여 코드로도 참여할 수 있다")
    void qrIsSingleUse() {
        Veh v = readyVehicle();
        Veh.Issued q = v.issue();
        assertThat(new Phone(port).join(q.token(), "하나").status()).isEqualTo(201);
        Res second = new Phone(port).join(q.token(), "둘");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.err()).isEqualTo("TOKEN_USED");

        Veh.Issued q2 = v.issue();
        Res byCode = Harness.call(port, "POST", "/api/join/requests", null,
                Map.of("code", q2.code().toLowerCase(), "nickname", "셋"));
        assertThat(byCode.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("9. 역할을 바꾸면 웹 화면이 실시간으로 바뀌고 이전 역할의 기능은 쓸 수 없다")
    void roleChangeIsLive() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        String pid = joined(v, p, "민수", "HVAC");
        assertThat(v.role(pid, "LIGHT").status()).isEqualTo(200);
        p.sock.await("STATE_UPDATE", n -> n.path("role").asText().equals("LIGHT"));
        p.command("HVAC_TEMPERATURE", Map.of("celsius", 22));
        p.sock.await("ERROR", n -> n.path("code").asText().equals("NOT_YOUR_ROLE"));
    }

    @Test
    @DisplayName("지원하지 않는 기능은 공유 중으로 표시되지 않고 요청도 NOT_SUPPORTED 로 끝난다")
    void unsupportedFeatureIsNotShownAsAvailable() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        String pid = joined(v, p, "서준", "LIGHT");
        v.access(pid, "LIGHT_COLOR", "CONTROL");
        JsonNode st = p.sock.await("STATE_UPDATE", n -> "NOT_SUPPORTED".equals(feature(n, "LIGHT_COLOR")));
        assertThat(feature(st, "LIGHT_COLOR")).isEqualTo("NOT_SUPPORTED");
        p.command("LIGHT_COLOR", Map.of("hex", "#FF0000"));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("차량이 응답하지 않으면 TIMED_OUT 으로 끝나고 APPLIED 로 표시되지 않는다")
    void commandTimesOut() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        joined(v, p, "민수", "MAP");
        p.command("NAV_DESTINATION", Map.of("name", "서울역", "lat", 37.55, "lng", 126.97));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("SENT_TO_VEHICLE"));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("TIMED_OUT"));
        assertThat(p.sock.tryAwait("COMMAND_RESULT", n -> n.path("status").asText().equals("APPLIED"), 500))
                .as("차량이 확인하지 않은 명령은 APPLIED 가 될 수 없다").isNull();
    }

    @Test
    @DisplayName("차량 API 인증: 토큰 없음/잘못된 서명/재사용된 챌린지는 거부된다")
    void vehicleAuthRejections() {
        assertThat(Harness.call(port, "POST", "/api/vehicle/sessions/open", null, Map.of("launchId", "x")).status())
                .isIn(401, 403);
        assertThat(Sock.rejected(port, "/ws/vehicle", Map.of())).isTrue();

        Veh v = Veh.registered(port);
        String nonce = Harness.call(port, "POST", "/api/vehicle/challenge", null,
                Map.of("installationId", v.installationId)).body().path("nonce").asText();
        Res bad = Harness.call(port, "POST", "/api/vehicle/verify", null, Map.of("installationId", v.installationId,
                "nonce", nonce, "signature", Veh.registered(port).sign(nonce)));
        assertThat(bad.status()).isEqualTo(401);
        Res replay = Harness.call(port, "POST", "/api/vehicle/verify", null, Map.of("installationId", v.installationId,
                "nonce", nonce, "signature", v.sign(nonce)));
        assertThat(replay.status()).as("챌린지는 일회용").isEqualTo(401);
    }

    @Test
    @DisplayName("동승자 소켓은 AUTH 없이는 아무것도 할 수 없다")
    void participantSocketRequiresAuth() {
        Sock s = Sock.connect(port, "/ws/participant", Map.of());
        s.send("VEHICLE_COMMAND", Map.of("action", "NAV_DESTINATION", "params", Map.of()));
        s.await("ERROR", n -> n.path("code").asText().equals("NOT_AUTHENTICATED"));
    }

    @Test
    @DisplayName("음악 담당: 권한 부여 전 차단 → 부여 후 MOCK 적용, 잘못된 명령은 거절")
    void musicRoleFlow() {
        Veh v = readyVehicle();
        v.autoApply();
        Phone p = new Phone(port);
        String pid = joined(v, p, "서준", "MUSIC");

        JsonNode st = p.sock.await("STATE_UPDATE", n -> n.path("role").asText().equals("MUSIC"));
        assertThat(feature(st, "MUSIC_VIEW")).isEqualTo("MOCK_ONLY");
        assertThat(feature(st, "MUSIC_CONTROL")).isEqualTo("PERMISSION_REQUIRED");

        p.command("MUSIC_CONTROL", Map.of("command", "NEXT"));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("PERMISSION_DENIED"));

        assertThat(v.access(pid, "MUSIC_CONTROL", "CONTROL").status()).isEqualTo(200);
        p.sock.await("STATE_UPDATE", n -> "MOCK_ONLY".equals(feature(n, "MUSIC_CONTROL")));

        p.command("MUSIC_CONTROL", Map.of("command", "VOLUME_UP"));
        p.sock.await("ERROR", n -> n.path("code").asText().equals("INVALID_PARAMS"));

        p.command("MUSIC_CONTROL", Map.of("command", "NEXT"));
        JsonNode ok = p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("APPLIED"));
        assertThat(ok.path("simulated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("결과를 확인할 수 없는 전달(DELIVERED)은 APPLIED 로 표시되지 않는다")
    void deliveredIsNotApplied() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        String pid = joined(v, p, "서준", "MUSIC");
        v.access(pid, "MUSIC_CONTROL", "CONTROL");
        p.sock.await("STATE_UPDATE", n -> "MOCK_ONLY".equals(feature(n, "MUSIC_CONTROL")));

        p.command("MUSIC_CONTROL", Map.of("command", "NEXT"));
        JsonNode cmd = v.sock.await("VEHICLE_COMMAND");
        v.sock.send("COMMAND_RESULT", Map.of("requestId", cmd.path("requestId").asText(), "status", "DELIVERED"));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("DELIVERED"));
        assertThat(p.sock.tryAwait("COMMAND_RESULT", n -> n.path("status").asText().equals("APPLIED"), 400)).isNull();
    }

    @Test
    @DisplayName("목적지 제안은 운전자 승인 대기 중에는 타임아웃되지 않고, 수락하면 적용된다")
    void navProposalWaitsForDriverThenApplies() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        joined(v, p, "민수", "MAP");
        p.command("NAV_DESTINATION", Map.of("name", "서울역", "lat", 37.55, "lng", 126.97));
        JsonNode cmd = v.sock.await("VEHICLE_COMMAND");
        String id = cmd.path("requestId").asText();
        v.sock.send("COMMAND_ACK", Map.of("requestId", id, "awaitingDriver", true));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("AWAITING_DRIVER"));

        assertThat(p.sock.tryAwait("COMMAND_RESULT", n -> n.path("status").asText().equals("TIMED_OUT"), 3500)).isNull();

        v.sock.send("COMMAND_ACK", Map.of("requestId", id));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("APPLYING"));
        v.sock.send("COMMAND_RESULT", Map.of("requestId", id, "status", "APPLIED", "simulated", true));
        p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("APPLIED"));
    }

    @Test
    @DisplayName("운전자가 거절하면 FAILED(DECLINED_BY_DRIVER)로 끝난다")
    void navProposalDeclinedByDriver() {
        Veh v = readyVehicle();
        Phone p = new Phone(port);
        joined(v, p, "민수", "MAP");
        p.command("NAV_DESTINATION", Map.of("name", "서울역", "lat", 37.55, "lng", 126.97));
        String id = v.sock.await("VEHICLE_COMMAND").path("requestId").asText();
        v.sock.send("COMMAND_ACK", Map.of("requestId", id, "awaitingDriver", true));
        v.sock.send("COMMAND_RESULT", Map.of("requestId", id, "status", "FAILED", "errorCode", "DECLINED_BY_DRIVER"));
        JsonNode r = p.sock.await("COMMAND_RESULT", n -> n.path("status").asText().equals("FAILED"));
        assertThat(r.path("errorCode").asText()).isEqualTo("DECLINED_BY_DRIVER");
    }

    @Test
    @DisplayName("차량이 값 범위를 보고하지 않은 기능은 제어 요청이 거절된다")
    void controlWithoutReportedRangeIsRefused() {
        Veh v = Veh.registered(port);
        v.open("launch-" + UUID.randomUUID());
        v.connect();
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("HVAC_VIEW", Map.of("status", "MOCK_ONLY"));
        f.put("HVAC_TEMPERATURE", Map.of("status", "MOCK_ONLY"));
        v.sock.send("CAPABILITY_REPORT", Map.of("mode", "MOCK", "driving", false, "features", f));
        Phone p = new Phone(port);
        String pid = joined(v, p, "지연", "HVAC");
        v.access(pid, "HVAC_TEMPERATURE", "CONTROL");
        p.sock.await("STATE_UPDATE", n -> "MOCK_ONLY".equals(feature(n, "HVAC_TEMPERATURE")));
        p.command("HVAC_TEMPERATURE", Map.of("celsius", 22));
        p.sock.await("ERROR", n -> n.path("code").asText().equals("INVALID_PARAMS"));
    }

    @Test
    @DisplayName("모바일 웹 페이지가 렌더링되고 모든 역할 화면을 포함한다")
    void joinPageRenders() throws Exception {
        java.net.http.HttpResponse<String> r = Harness.HTTP.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/join")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("view-map", "view-hvac", "view-light", "view-music", "음악 담당");
        assertThat(r.headers().firstValue("Content-Security-Policy")).isPresent();
    }

    private static String feature(JsonNode state, String code) {
        for (JsonNode f : state.path("features")) {
            if (f.path("code").asText().equals(code)) {
                return f.path("status").asText();
            }
        }
        return null;
    }
}
