package com.codrive.server.service;

import com.codrive.server.domain.VehicleInstallation;
import com.codrive.server.repo.VehicleInstallationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class VehicleAuthService {
    private static final Duration NONCE_TTL = Duration.ofSeconds(60);
    private static final Duration TOKEN_TTL = Duration.ofHours(12);

    private record Nonce(UUID installationId, Instant expiresAt) {
    }

    private record Auth(UUID installationId, Instant expiresAt) {
    }

    private final VehicleInstallationRepository installations;
    private final TokenService tokens;

    private final Map<String, Nonce> nonces = new ConcurrentHashMap<>();
    private final Map<String, Auth> authTokens = new ConcurrentHashMap<>();

    @Transactional
    public UUID register(String displayName, String publicKeyBase64) {
        parsePublicKey(publicKeyBase64);
        VehicleInstallation v = new VehicleInstallation();
        v.setInstallationId(UUID.randomUUID());
        v.setDisplayName(displayName == null || displayName.isBlank() ? "CO-DRIVE 차량" : displayName.trim());
        v.setPublicKey(publicKeyBase64);
        v.setCreatedAt(Instant.now());
        installations.save(v);
        return v.getInstallationId();
    }

    public String challenge(UUID installationId) {
        VehicleInstallation v = installations.findById(installationId)
                .orElseThrow(() -> ApiException.unauthorized("UNKNOWN_INSTALLATION", "등록되지 않은 차량 앱입니다."));
        if (v.getRevokedAt() != null) {
            throw ApiException.unauthorized("REVOKED", "폐기된 차량 앱입니다.");
        }
        purgeExpired();
        String nonce = tokens.newToken();
        nonces.put(nonce, new Nonce(installationId, Instant.now().plus(NONCE_TTL)));
        return nonce;
    }

    @Transactional
    public String verify(UUID installationId, String nonce, String signatureBase64) {
        Nonce n = nonce == null ? null : nonces.remove(nonce);
        if (n == null || !n.installationId().equals(installationId) || n.expiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("BAD_CHALLENGE", "유효하지 않은 인증 요청입니다.");
        }
        VehicleInstallation v = installations.findById(installationId)
                .orElseThrow(() -> ApiException.unauthorized("UNKNOWN_INSTALLATION", "등록되지 않은 차량 앱입니다."));
        if (v.getRevokedAt() != null) {
            throw ApiException.unauthorized("REVOKED", "폐기된 차량 앱입니다.");
        }
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(parsePublicKey(v.getPublicKey()));
            sig.update(nonce.getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(Base64.getDecoder().decode(signatureBase64))) {
                throw ApiException.unauthorized("BAD_SIGNATURE", "서명 검증에 실패했습니다.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.unauthorized("BAD_SIGNATURE", "서명 검증에 실패했습니다.");
        }
        v.setLastSeenAt(Instant.now());
        String raw = tokens.newToken();
        authTokens.put(tokens.hash(raw), new Auth(installationId, Instant.now().plus(TOKEN_TTL)));
        return raw;
    }

    public Optional<UUID> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Auth a = authTokens.get(tokens.hash(rawToken));
        if (a == null || a.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(a.installationId());
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        nonces.values().removeIf(n -> n.expiresAt().isBefore(now));
        authTokens.values().removeIf(a -> a.expiresAt().isBefore(now));
    }

    private static PublicKey parsePublicKey(String b64) {
        try {
            byte[] der = Base64.getDecoder().decode(b64);
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            return key;
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_PUBLIC_KEY", "공개키 형식이 올바르지 않습니다. (EC, X.509 DER, Base64)");
        }
    }
}
