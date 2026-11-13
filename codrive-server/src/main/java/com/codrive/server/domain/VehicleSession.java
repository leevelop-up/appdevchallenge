package com.codrive.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_sessions")
@Getter @Setter @NoArgsConstructor
public class VehicleSession {
    public enum Status { ACTIVE, SUSPENDED, ENDED }

    @Id
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "installation_id")
    private UUID installationId;
    private String launchId;
    private String alias;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant lastVehicleSeenAt;
    private Instant suspendedAt;
    private Instant endedAt;
    private String endReason;

    public boolean isLive() {
        return status != Status.ENDED;
    }
}
