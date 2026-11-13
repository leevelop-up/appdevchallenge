package com.codrive.server.config;

import com.codrive.server.service.SessionService;
import com.codrive.server.service.VehicleAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {
    private static final List<String> VEHICLE_OPEN = List.of(
            "/api/vehicle/register", "/api/vehicle/challenge", "/api/vehicle/verify");

    private final VehicleAuthService vehicleAuth;
    private final SessionService sessionService;

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:; "
                                        + "style-src 'self'; script-src 'self'; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/join", "/css/**", "/js/**", "/error", "/actuator/health").permitAll()
                        .requestMatchers("/api/join/**", "/ws/**").permitAll()
                        .requestMatchers(VEHICLE_OPEN.toArray(String[]::new)).permitAll()
                        .requestMatchers("/api/vehicle/**").hasRole("VEHICLE")
                        .requestMatchers("/api/places/**").hasRole("PARTICIPANT")
                        .anyRequest().denyAll())
                .addFilterBefore(new TokenFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private class TokenFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String path = req.getRequestURI().substring(req.getContextPath().length());
            if (path.startsWith("/api/vehicle/") && !VEHICLE_OPEN.contains(path)) {
                String header = req.getHeader("Authorization");
                String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
                UUID inst = vehicleAuth.authenticate(token).orElse(null);
                if (inst != null) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            inst, null, List.of(new SimpleGrantedAuthority("ROLE_VEHICLE"))));
                }
            } else if (path.startsWith("/api/places/")) {
                var id = sessionService.authenticatePhone(req.getHeader("X-Participant-Token"));
                if (id.isPresent() && id.get().participant() != null && id.get().participant().isActive()) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            id.get().participant().getParticipantId(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT"))));
                }
            }
            chain.doFilter(req, res);
        }
    }
}
