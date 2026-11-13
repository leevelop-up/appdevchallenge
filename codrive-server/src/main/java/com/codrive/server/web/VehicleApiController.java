package com.codrive.server.web;

import com.codrive.server.domain.Feature;
import com.codrive.server.domain.Participant;
import com.codrive.server.domain.VehicleSession;
import com.codrive.server.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/vehicle")
@RequiredArgsConstructor
public class VehicleApiController {
    record RegisterReq(String displayName, @NotBlank String publicKey) {
    }

    record ChallengeReq(@NotNull UUID installationId) {
    }

    record VerifyReq(@NotNull UUID installationId, @NotBlank String nonce, @NotBlank String signature) {
    }

    record OpenReq(@NotBlank String launchId) {
    }

    record RoleReq(@NotNull Participant.Role role) {
    }

    record AccessReq(@NotNull Feature.Access access) {
    }

    record EndReq(String reason) {
    }

    record OpenRes(UUID sessionId, String alias, String status, Instant expiresAt, boolean created,
                   Views.VehicleSnapshot snapshot) {
    }

    private static final Set<String> END_REASONS = Set.of("USER_ENDED_ALL", "POWER_OFF");

    private final VehicleAuthService auth;
    private final SessionService sessions;
    private final SnapshotService snapshots;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@Valid @RequestBody RegisterReq req) {
        return Map.of("installationId", auth.register(req.displayName(), req.publicKey()));
    }

    @PostMapping("/challenge")
    Map<String, String> challenge(@Valid @RequestBody ChallengeReq req) {
        return Map.of("nonce", auth.challenge(req.installationId()));
    }

    @PostMapping("/verify")
    Map<String, String> verify(@Valid @RequestBody VerifyReq req) {
        return Map.of("accessToken", auth.verify(req.installationId(), req.nonce(), req.signature()));
    }

    @PostMapping("/sessions/open")
    OpenRes open(@AuthenticationPrincipal UUID inst, @Valid @RequestBody OpenReq req) {
        SessionService.OpenResult r = sessions.openSession(inst, req.launchId());
        VehicleSession s = r.session();
        return new OpenRes(s.getSessionId(), s.getAlias(), s.getStatus().name(), s.getExpiresAt(), r.created(),
                snapshots.buildAll(s.getSessionId()).vehicle());
    }

    @GetMapping("/sessions/{sid}/snapshot")
    Views.VehicleSnapshot snapshot(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid) {
        sessions.ownedSession(inst, sid);
        return snapshots.buildAll(sid).vehicle();
    }

    @PostMapping("/sessions/{sid}/join-tokens")
    Views.IssuedToken issue(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid) {
        return sessions.issueJoinToken(inst, sid);
    }

    @PostMapping("/sessions/{sid}/requests/{rid}/approve")
    void approve(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid, @PathVariable UUID rid) {
        sessions.approve(inst, sid, rid);
    }

    @PostMapping("/sessions/{sid}/requests/{rid}/reject")
    void reject(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid, @PathVariable UUID rid) {
        sessions.reject(inst, sid, rid);
    }

    @PutMapping("/sessions/{sid}/participants/{pid}/role")
    void role(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid, @PathVariable UUID pid,
              @Valid @RequestBody RoleReq req) {
        sessions.assignRole(inst, sid, pid, req.role());
    }

    @PutMapping("/sessions/{sid}/participants/{pid}/permissions/{feature}")
    void access(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid, @PathVariable UUID pid,
                @PathVariable String feature, @Valid @RequestBody AccessReq req) {
        sessions.setAccess(inst, sid, pid, feature, req.access());
    }

    @DeleteMapping("/sessions/{sid}/participants/{pid}")
    void remove(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid, @PathVariable UUID pid) {
        sessions.removeParticipant(inst, sid, pid);
    }

    @PostMapping("/sessions/{sid}/end")
    void end(@AuthenticationPrincipal UUID inst, @PathVariable UUID sid, @RequestBody(required = false) EndReq req) {
        String reason = req != null && req.reason() != null && END_REASONS.contains(req.reason())
                ? req.reason() : "USER_ENDED_ALL";
        sessions.endSession(inst, sid, reason);
    }
}
