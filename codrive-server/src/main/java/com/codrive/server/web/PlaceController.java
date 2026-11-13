package com.codrive.server.web;

import com.codrive.server.service.ApiException;
import com.codrive.server.service.GeocodingService;
import com.codrive.server.service.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {
    private final GeocodingService geocoding;
    private final RateLimiter limiter;

    @GetMapping("/search")
    Map<String, Object> search(@AuthenticationPrincipal UUID participantId, @RequestParam String q) {
        if (!limiter.tryAcquire("place:" + participantId, 1, 1100)) {
            throw ApiException.tooMany("검색은 1초에 한 번만 할 수 있습니다.");
        }
        List<GeocodingService.Place> places = geocoding.search(q);
        return Map.of("enabled", geocoding.enabled(), "places", places);
    }
}
