package com.codrive.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.codrive.server.service.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "server.servlet.context-path=/codrive")
class ContextPathTest {
    @LocalServerPort
    int port;
    @Autowired
    RateLimiter limiter;

    @BeforeAll
    static void usePrefix() {
        Harness.prefix = "/codrive";
    }

    @AfterAll
    static void clearPrefix() {
        Harness.prefix = "";
    }

    @BeforeEach
    void reset() {
        limiter.reset();
    }

    @Test
    @DisplayName("/codrive 하위 경로로 배포해도 차량 토큰 인증이 동작한다")
    void vehicleTokenWorksUnderContextPath() {
        Harness.Veh v = Harness.Veh.registered(port);
        JsonNode open = v.open("launch-ctx");
        assertThat(open.path("created").asBoolean()).isTrue();
        assertThat(v.issue().code()).hasSize(6);
    }

    @Test
    @DisplayName("/codrive 하위 경로에서도 토큰 없는 차량 API 는 거절된다")
    void vehicleApiStillRequiresToken() {
        Harness.Res r = Harness.call(port, "POST", "/api/vehicle/sessions/open", null, java.util.Map.of("launchId", "x"));
        assertThat(r.status()).isIn(401, 403);
    }
}
