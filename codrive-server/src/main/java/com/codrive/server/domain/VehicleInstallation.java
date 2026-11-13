package com.codrive.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_installations")
@Getter @Setter @NoArgsConstructor
public class VehicleInstallation {
    @Id
    @Column(name = "installation_id")
    private UUID installationId;
    private String displayName;
    @Column(columnDefinition = "text")
    private String publicKey;
    private Instant createdAt;
    private Instant lastSeenAt;
    private Instant revokedAt;
}
