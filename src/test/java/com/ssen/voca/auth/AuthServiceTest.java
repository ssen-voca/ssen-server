package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AuthServiceTest {

	@Autowired
	private AuthService authService;

	@Autowired
	private JwtService jwtService;

	@Test
	void signupCreatesUserAndReturnsTokens() {
		TokenResponse tokens = authService.signup(new SignupRequest("김학생", "new-student@example.com", "password123"));

		assertThat(jwtService.parseAccessToken(tokens.accessToken()).get("email")).isEqualTo("new-student@example.com");
		assertThat(jwtService.parseRefreshToken(tokens.refreshToken())).isNotNull();
	}

	@Test
	void signupWithDuplicateEmailThrows() {
		authService.signup(new SignupRequest("김학생", "dup@example.com", "password123"));

		assertThatThrownBy(() -> authService.signup(new SignupRequest("이학생", "dup@example.com", "password456")))
				.isInstanceOf(EmailAlreadyExistsException.class);
	}

	@Test
	void loginWithCorrectPasswordReturnsTokens() {
		authService.signup(new SignupRequest("김학생", "login@example.com", "password123"));

		TokenResponse tokens = authService.login(new LoginRequest("login@example.com", "password123"));

		assertThat(jwtService.parseAccessToken(tokens.accessToken())).isNotNull();
	}

	@Test
	void loginWithWrongPasswordThrows() {
		authService.signup(new SignupRequest("김학생", "wrongpw@example.com", "password123"));

		assertThatThrownBy(() -> authService.login(new LoginRequest("wrongpw@example.com", "wrong-password")))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void refreshWithValidRefreshTokenReturnsNewAccessToken() {
		TokenResponse tokens = authService.signup(new SignupRequest("김학생", "refresh@example.com", "password123"));

		var accessTokenResponse = authService.refresh(new RefreshRequest(tokens.refreshToken()));

		assertThat(jwtService.parseAccessToken(accessTokenResponse.accessToken()).get("email"))
				.isEqualTo("refresh@example.com");
	}

	@Test
	void refreshWithAccessTokenThrows() {
		TokenResponse tokens = authService.signup(new SignupRequest("김학생", "badrefresh@example.com", "password123"));

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest(tokens.accessToken())))
				.isInstanceOf(InvalidTokenException.class);
	}
}
