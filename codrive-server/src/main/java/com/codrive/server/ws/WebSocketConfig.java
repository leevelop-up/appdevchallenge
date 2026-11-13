package com.codrive.server.ws;

import com.codrive.server.config.AppProperties;
import com.codrive.server.service.VehicleAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final VehicleSocketHandler vehicleHandler;
    private final ParticipantSocketHandler participantHandler;
    private final VehicleAuthService vehicleAuth;
    private final AppProperties props;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        HandshakeInterceptor vehicleAuthInterceptor = new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h,
                                           Map<String, Object> attrs) {
                String header = req.getHeaders().getFirst("Authorization");
                String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
                UUID inst = vehicleAuth.authenticate(token).orElse(null);
                if (inst == null) {
                    res.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
                attrs.put(VehicleSocketHandler.ATTR_INSTALLATION, inst);
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception ex) {
            }
        };
        String origin = originOf(props.publicBaseUrl());
        registry.addHandler(vehicleHandler, "/ws/vehicle").addInterceptors(vehicleAuthInterceptor)
                .setAllowedOriginPatterns("*");
        registry.addHandler(participantHandler, "/ws/participant")
                .setAllowedOriginPatterns(origin, "http://localhost:*", "http://127.0.0.1:*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(64 * 1024);
        return c;
    }

    private static String originOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return url;
        }
    }
}
