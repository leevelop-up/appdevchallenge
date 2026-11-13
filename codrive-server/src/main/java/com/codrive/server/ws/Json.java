package com.codrive.server.ws;

import com.codrive.server.domain.Feature;
import com.codrive.server.service.CapabilityStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

final class Json {
    private Json() {
    }

    static UUID uuid(JsonNode n, String field) {
        try {
            return UUID.fromString(n.path(field).asText(""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Map<Feature, CapabilityStore.Entry> features(JsonNode node) {
        Map<Feature, CapabilityStore.Entry> out = new EnumMap<>(Feature.class);
        node.fields().forEachRemaining(e -> {
            Feature f = Feature.parse(e.getKey());
            if (f == null) {
                return;
            }
            Feature.Status st;
            try {
                st = Feature.Status.valueOf(e.getValue().path("status").asText());
            } catch (IllegalArgumentException ex) {
                st = Feature.Status.NOT_SUPPORTED;
            }
            JsonNode min = e.getValue().path("min");
            JsonNode max = e.getValue().path("max");
            out.put(f, new CapabilityStore.Entry(st, min.isNumber() ? min.asDouble() : null,
                    max.isNumber() ? max.asDouble() : null));
        });
        return out;
    }
}
