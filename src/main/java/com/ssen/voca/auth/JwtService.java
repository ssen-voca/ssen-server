package com.ssen.voca.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

	private static final String TYPE_CLAIM = "type";
	private static final String REFRESH_TYPE = "refresh";

	private final SecretKey key;
	private final long accessTokenTtlMillis;
	private final long refreshTokenTtlMillis;

	public JwtService(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.access-ttl-millis:1800000}") long accessTokenTtlMillis,
			@Value("${app.jwt.refresh-ttl-millis:1209600000}") long refreshTokenTtlMillis) {
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.accessTokenTtlMillis = accessTokenTtlMillis;
		this.refreshTokenTtlMillis = refreshTokenTtlMillis;
	}

	public String generateAccessToken(Long userId, String email) {
		Date now = new Date();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim("email", email)
				.issuedAt(now)
				.expiration(new Date(now.getTime() + accessTokenTtlMillis))
				.signWith(key)
				.compact();
	}

	public String generateRefreshToken(Long userId) {
		Date now = new Date();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim(TYPE_CLAIM, REFRESH_TYPE)
				.issuedAt(now)
				.expiration(new Date(now.getTime() + refreshTokenTtlMillis))
				.signWith(key)
				.compact();
	}

	public Claims parseAccessToken(String token) {
		Claims claims = parse(token);
		if (claims.get(TYPE_CLAIM) != null) {
			throw new InvalidTokenException("액세스 토큰이 아닙니다.");
		}
		return claims;
	}

	public Claims parseRefreshToken(String token) {
		Claims claims = parse(token);
		if (!REFRESH_TYPE.equals(claims.get(TYPE_CLAIM))) {
			throw new InvalidTokenException("리프레시 토큰이 아닙니다.");
		}
		return claims;
	}

	private Claims parse(String token) {
		try {
			return Jwts.parser()
					.verifyWith(key)
					.build()
					.parseSignedClaims(token)
					.getPayload();
		} catch (JwtException | IllegalArgumentException e) {
			throw new InvalidTokenException("유효하지 않은 토큰입니다.");
		}
	}
}
