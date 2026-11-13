package com.codrive.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "participant_permissions")
@IdClass(ParticipantPermission.Key.class)
@Getter @Setter @NoArgsConstructor
public class ParticipantPermission {
    @Id
    @Column(name = "participant_id")
    private UUID participantId;
    @Id
    @Column(name = "feature_code")
    private String featureCode;
    @Enumerated(EnumType.STRING)
    private Feature.Access accessLevel;
    private String capabilityStatus;
    private Instant grantedAt;
    private Instant revokedAt;

    public ParticipantPermission(UUID participantId, String featureCode, Feature.Access accessLevel, Instant now) {
        this.participantId = participantId;
        this.featureCode = featureCode;
        this.accessLevel = accessLevel;
        this.grantedAt = now;
    }

    @Getter @Setter @NoArgsConstructor
    public static class Key implements Serializable {
        private UUID participantId;
        private String featureCode;

        public Key(UUID participantId, String featureCode) {
            this.participantId = participantId;
            this.featureCode = featureCode;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(participantId, k.participantId)
                    && Objects.equals(featureCode, k.featureCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(participantId, featureCode);
        }
    }
}
