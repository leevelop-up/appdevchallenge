package com.codrive.vehicle.model;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ProposalTracker {
    public static final class Proposal {
        public final String requestId;
        public final String from;
        public final String action;
        public final String placeName;
        public final JSONObject params;
        private final long expiresAtNanos;

        public Proposal(String requestId, String from, String action, JSONObject params, long ttlMillis) {
            this.requestId = requestId;
            this.from = from;
            this.action = action;
            this.params = params;
            this.placeName = params.optString("name", "");
            this.expiresAtNanos = System.nanoTime() + ttlMillis * 1_000_000L;
        }

        boolean isExpired(long nowNanos) {
            return nowNanos - expiresAtNanos >= 0;
        }
    }

    private final List<Proposal> items = new ArrayList<>();

    public synchronized void add(Proposal p) {
        items.add(p);
    }

    public synchronized List<Proposal> snapshot() {
        return new ArrayList<>(items);
    }

    public synchronized Proposal take(String requestId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).requestId.equals(requestId)) {
                return items.remove(i);
            }
        }
        return null;
    }

    public synchronized List<Proposal> takeExpired(long nowNanos) {
        List<Proposal> out = new ArrayList<>();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).isExpired(nowNanos)) {
                out.add(0, items.remove(i));
            }
        }
        return out;
    }

    public synchronized List<Proposal> clear() {
        List<Proposal> out = new ArrayList<>(items);
        items.clear();
        return out;
    }
}
