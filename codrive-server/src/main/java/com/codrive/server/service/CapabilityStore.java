package com.codrive.server.service;

import com.codrive.server.domain.Feature;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CapabilityStore {
    public record Entry(Feature.Status status, Double min, Double max) {
    }

    public record Report(String mode, boolean driving, Map<Feature, Entry> features) {
    }

    private final Map<UUID, Report> bySession = new ConcurrentHashMap<>();

    public void put(UUID sessionId, Report report) {
        bySession.put(sessionId, report);
    }

    public Report get(UUID sessionId) {
        return bySession.get(sessionId);
    }

    public Entry entry(UUID sessionId, Feature f) {
        Report r = bySession.get(sessionId);
        return r == null ? null : r.features().get(f);
    }

    public void clear(UUID sessionId) {
        bySession.remove(sessionId);
    }
}
