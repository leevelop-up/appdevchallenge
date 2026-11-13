package com.codrive.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "command_logs")
@Getter @Setter @NoArgsConstructor
public class CommandLog {
    public enum Status {
        RECEIVED, AUTHORIZED, SENT_TO_VEHICLE,

        AWAITING_DRIVER,
        APPLYING, APPLIED,
        FAILED, PERMISSION_DENIED, NOT_SUPPORTED, TIMED_OUT,

        DELIVERED;

        public boolean isFinal() {
            return this == APPLIED || this == FAILED || this == PERMISSION_DENIED
                    || this == NOT_SUPPORTED || this == TIMED_OUT || this == DELIVERED;
        }
    }

    @Id
    @Column(name = "request_id")
    private UUID requestId;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "participant_id")
    private UUID participantId;
    private String action;
    @Column(columnDefinition = "text")
    private String params;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String errorCode;
    private boolean simulated;
    private Instant createdAt;
    private Instant completedAt;
    private Instant updatedAt;
}
