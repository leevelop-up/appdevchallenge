package com.codrive.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicBaseUrl,
        int qrTtlSeconds,
        int joinRequestTtlSeconds,
        int sessionMaxHours,
        int vehicleGraceSeconds,
        int vehicleHeartbeatTimeoutSeconds,
        int commandTimeoutSeconds,
        int proposalTimeoutSeconds,
        int maxParticipants,
        Geocoding geocoding) {
    public record Geocoding(boolean enabled, String baseUrl, String userAgent) {
    }
}
