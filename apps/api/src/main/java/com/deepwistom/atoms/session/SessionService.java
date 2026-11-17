package com.deepwistom.atoms.session;

import com.deepwistom.atoms.common.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    public static final String ATTRIBUTE = "atoms.owner";
    private static final Duration LIFETIME = Duration.ofDays(30);
    private final SessionRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final boolean secure;
    private final String cookieName;

    public SessionService(SessionRepository repository, @Value("${atoms.cookie-secure}") boolean secure) {
        this.repository = repository;
        this.secure = secure;
        this.cookieName = secure ? "__Host-atoms_owner" : "atoms_owner";
    }

    public record Owner(String id, String csrfToken, Instant expiresAt) {}
    public record SessionResponse(String sessionId, String csrfToken, Instant expiresAt) {}

    public SessionResponse bootstrap(HttpServletRequest request, HttpServletResponse response) {
        String secret = cookie(request);
        if (secret != null) {
            var existing = repository.findBySecretHash(sha256(secret));
            if (existing.isPresent() && existing.get().expiresAt().isAfter(Instant.now())) {
                Owner owner = renew(existing.get().id(), secret, response);
                return new SessionResponse(owner.id(), owner.csrfToken(), owner.expiresAt());
            }
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expires = now.plus(LIFETIME);
        String id = UUID.randomUUID().toString();
        repository.create(id, sha256(secret), now, expires);
        setCookie(response, secret);
        return new SessionResponse(id, csrf(secret), expires);
    }

    public Owner authenticate(HttpServletRequest request, HttpServletResponse response) {
        String secret = cookie(request);
        if (secret == null) throw new ApiException(401, "SESSION_REQUIRED", "请重新连接以建立匿名会话。");
        var session = repository.findBySecretHash(sha256(secret))
                .orElseThrow(() -> new ApiException(401, "SESSION_REQUIRED", "匿名会话不可用，请重新连接。"));
        if (!session.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(401, "SESSION_EXPIRED", "匿名会话已过期，请重新连接。");
        }
        return renew(session.id(), secret, response);
    }

    public Owner current(HttpServletRequest request) {
        return (Owner) request.getAttribute(ATTRIBUTE);
    }

    public void checkCsrf(Owner owner, String supplied) {
        if (supplied == null || !MessageDigest.isEqual(
                owner.csrfToken().getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403, "CSRF_REJECTED", "页面状态已失效，请刷新后重试。");
        }
    }

    private Owner renew(String id, String secret, HttpServletResponse response) {
        Instant expires = Instant.now().plus(LIFETIME);
        repository.renew(id, expires);
        setCookie(response, secret);
        return new Owner(id, csrf(secret), expires);
    }

    private void setCookie(HttpServletResponse response, String secret) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName, secret)
                .httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(LIFETIME).build().toString());
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        var cookies = Arrays.stream(request.getCookies()).filter(item -> cookieName.equals(item.getName())).toList();
        if (cookies.size() != 1) return null;
        String value = cookies.getFirst().getValue();
        return value.matches("[A-Za-z0-9_-]{43}") ? value : null;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("Required SHA-256 algorithm missing", error);
        }
    }

    private String csrf(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal("atoms-csrf-v1".getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Required HMAC algorithm missing", error);
        }
    }
}
