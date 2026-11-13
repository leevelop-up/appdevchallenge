package com.codrive.server.service;

import java.util.UUID;

public final class Events {
    private Events() {
    }

    public record ToVehicle(UUID installationId, String type, Object payload) {
    }

    public record ToPhone(UUID requestId, String type, Object payload, boolean closeAfter) {
    }

    public record SessionChanged(UUID sessionId) {
    }
}
