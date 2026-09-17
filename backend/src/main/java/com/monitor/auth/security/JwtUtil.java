package com.monitor.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析（HS256）。
 */
@Component
public class JwtUtil {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_UID = "uid";
    /**
     * 签发时的令牌版本（对应 {@code sys_user.token_version}）。
     * 鉴权时与库中当前值比对，不等即视为已失效——见 {@code JwtAuthFilter}。
     */
    public static final String CLAIM_VER = "ver";

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(@Value("${monitor.jwt.secret}") String secret,
                   @Value("${monitor.jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * @param tokenVersion 签发时的 {@code sys_user.token_version}；之后该字段一旦被递增，
     *                     本令牌立即失效（用于停用/降权/改密/登出）
     */
    public String generateToken(Long userId, String username, String role, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_UID, String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_VER, tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验签名/过期，失败抛异常。
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
