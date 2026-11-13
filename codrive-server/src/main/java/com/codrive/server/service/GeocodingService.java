package com.codrive.server.service;

import com.codrive.server.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Service
public class GeocodingService {
    public record Place(String name, String address, double lat, double lng) {
    }

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);
    private final AppProperties.Geocoding cfg;
    private final ObjectMapper mapper;
    private final RestClient client;

    public GeocodingService(AppProperties props, ObjectMapper mapper) {
        this.cfg = props.geocoding();
        this.mapper = mapper;
        this.client = RestClient.builder().baseUrl(cfg.baseUrl()).build();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    public List<Place> search(String query) {
        List<Place> out = new ArrayList<>();
        if (!cfg.enabled() || query == null || query.isBlank() || query.length() > 100) {
            return out;
        }
        try {
            String body = client.get()
                    .uri(u -> u.path("/search").queryParam("q", query).queryParam("format", "jsonv2")
                            .queryParam("limit", 5).queryParam("accept-language", "ko").build())
                    .header("User-Agent", cfg.userAgent())
                    .retrieve().body(String.class);
            JsonNode arr = mapper.readTree(body);
            for (JsonNode n : arr) {
                String display = n.path("display_name").asText("");
                String name = n.path("name").asText("");
                out.add(new Place(name.isBlank() ? display.split(",")[0].trim() : name, display,
                        n.path("lat").asDouble(), n.path("lon").asDouble()));
            }
        } catch (Exception e) {
            log.warn("geocoding failed: {}", e.toString());
        }
        return out;
    }
}
