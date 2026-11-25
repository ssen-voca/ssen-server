package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.auth.dto.LoginRequest;
import com.ssen.voca.auth.dto.RefreshRequest;
import com.ssen.voca.auth.dto.SignupRequest;
import com.ssen.voca.auth.dto.TokenResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AuthServiceTest {

	private static final String IP = "127.0.0.1";

	private String school;

	@BeforeEach
	void newSchool() {
		school = "서비스학교-" + UUID.randomUUID();
	}

	@Autowired
	private AuthService authService;

	@Autowired
	private JwtService jwtService;

	@Test
	void signupCreatesUserAndReturnsTokens() {
		TokenResponse tokens = authService.signup(new SignupRequest(school, "서비스가입", "1234"), IP);

		assertThat(jwtService.parseAccessToken(tokens.accessToken()).get("name")).isEqualTo("서비스가입");
		assertThat(jwtService.parseRefreshToken(tokens.refreshToken())).isNotNull();
	}

	@Test
	void signupWithSameNameAndPinThrows() {
		authService.signup(new SignupRequest(school, "서비스중복", "1234"), IP);

		assertThatThrownBy(() -> authService.signup(new SignupRequest(school, "서비스중복", "1234"), IP))
				.isInstanceOf(StudentAlreadyExistsException.class);
	}

	@Test
	void signupWithSameNameAndDifferentPinCreatesSeparateAccounts() {
		TokenResponse first = authService.signup(new SignupRequest(school, "서비스동명", "1111"), IP);
		TokenResponse second = authService.signup(new SignupRequest(school, "서비스동명", "2222"), IP);

		assertThat(jwtService.parseAccessToken(first.accessToken()).getSubject())
				.isNotEqualTo(jwtService.parseAccessToken(second.accessToken()).getSubject());
	}

	@Test
	void signupNormalizesNameWhitespaceAndCase() {
		authService.signup(new SignupRequest(school, " 서비스  민준 ", "1234"), IP);
		authService.signup(new SignupRequest(school, "Service  Kim", "1234"), IP);

		assertThatThrownBy(() -> authService.signup(new SignupRequest(school, "서비스 민준", "1234"), IP))
				.isInstanceOf(StudentAlreadyExistsException.class);
		assertThatThrownBy(() -> authService.signup(new SignupRequest(school, "  sERVICE kIM ", "1234"), IP))
				.isInstanceOf(StudentAlreadyExistsException.class);
	}

	@Test
	void loginWithCorrectPinReturnsTokens() {
		authService.signup(new SignupRequest(school, "서비스로그인", "1234"), IP);

		TokenResponse tokens = authService.login(new LoginRequest(school, "서비스로그인", "1234"), IP);

		assertThat(jwtService.parseAccessToken(tokens.accessToken())).isNotNull();
	}

	@Test
	void loginWithWrongPinThrows() {
		authService.signup(new SignupRequest(school, "서비스오답", "1234"), IP);

		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "서비스오답", "9999"), IP))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void loginWithUnknownNameThrows() {
		assertThatThrownBy(() -> authService.login(new LoginRequest(school, "서비스없는사람", "1234"), IP))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void loginPicksTheAccountWhoseHashMatches() {
		TokenResponse first = authService.signup(new SignupRequest(school, "서비스동명로그인", "1111"), IP);
		TokenResponse second = authService.signup(new SignupRequest(school, "서비스동명로그인", "2222"), IP);

		String secondLogin = authService.login(new LoginRequest(school, "서비스동명로그인", "2222"), IP).accessToken();

		assertThat(jwtService.parseAccessToken(secondLogin).getSubject())
				.isEqualTo(jwtService.parseAccessToken(second.accessToken()).getSubject())
				.isNotEqualTo(jwtService.parseAccessToken(first.accessToken()).getSubject());
	}

	@Test
	void refreshWithValidRefreshTokenReturnsNewAccessToken() {
		TokenResponse tokens = authService.signup(new SignupRequest(school, "서비스갱신", "1234"), IP);

		var accessTokenResponse = authService.refresh(new RefreshRequest(tokens.refreshToken()));

		assertThat(jwtService.parseAccessToken(accessTokenResponse.accessToken()).get("name"))
				.isEqualTo("서비스갱신");
	}

	@Test
	void refreshWithAccessTokenThrows() {
		TokenResponse tokens = authService.signup(new SignupRequest(school, "서비스갱신오류", "1234"), IP);

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest(tokens.accessToken())))
				.isInstanceOf(InvalidTokenException.class);
	}
}
