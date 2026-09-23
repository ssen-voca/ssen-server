package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

	private final JwtService jwtService =
			new JwtService("test-secret-key-that-is-at-least-32-bytes-long-1234567890", 1_800_000L, 1_209_600_000L);

	@Test
	void generatesAndParsesAccessToken() {
		String token = jwtService.generateAccessToken(1L, "student@example.com");

		Claims claims = jwtService.parseAccessToken(token);

		assertThat(claims.getSubject()).isEqualTo("1");
		assertThat(claims.get("email")).isEqualTo("student@example.com");
	}

	@Test
	void generatesAndParsesRefreshToken() {
		String token = jwtService.generateRefreshToken(1L);

		Claims claims = jwtService.parseRefreshToken(token);

		assertThat(claims.getSubject()).isEqualTo("1");
	}

	@Test
	void rejectsRefreshTokenAsAccessToken() {
		String refreshToken = jwtService.generateRefreshToken(1L);

		assertThatThrownBy(() -> jwtService.parseAccessToken(refreshToken))
				.isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void rejectsAccessTokenAsRefreshToken() {
		String accessToken = jwtService.generateAccessToken(1L, "student@example.com");

		assertThatThrownBy(() -> jwtService.parseRefreshToken(accessToken))
				.isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void rejectsGarbageToken() {
		assertThatThrownBy(() -> jwtService.parseAccessToken("not-a-jwt"))
				.isInstanceOf(InvalidTokenException.class);
	}
}
