package com.codrive.server.service;

import com.codrive.server.domain.Feature;
import com.codrive.server.domain.Participant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Views {
    private Views() {
    }

    public record FeatureView(String code, String kind, String access, String status, Double min, Double max) {
    }

    public record ParticipantView(UUID participantId, String nickname, Participant.Role role,
                                  Participant.ConnectionStatus connectionStatus, List<FeatureView> features) {
    }

    public record PendingView(UUID requestId, String nickname, Instant requestedAt) {
    }

    public record VehicleSnapshot(UUID sessionId, String alias, String sessionStatus, int maxParticipants,
                                  String capabilityMode, boolean driving, Instant expiresAt,
                                  List<ParticipantView> participants, List<PendingView> pendingRequests) {
    }

    public record PhoneState(String status, String nickname, String sessionAlias, String sessionStatus,
                             Participant.Role role, int connectedCount, int maxParticipants,
                             String capabilityMode, List<FeatureView> features) {
    }

    public record IssuedToken(String token, String code, Instant expiresAt, long ttlSeconds, String joinUrl) {
    }

    public record JoinPreview(String sessionAlias, int connectedCount, int maxParticipants, boolean joinable,
                              String reason) {
    }
}
